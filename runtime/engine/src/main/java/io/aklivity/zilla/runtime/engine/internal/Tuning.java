/*
 * Copyright 2021-2024 Aklivity Inc.
 *
 * Aklivity licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.aklivity.zilla.runtime.engine.internal;

import static java.nio.ByteOrder.nativeOrder;
import static java.nio.file.StandardOpenOption.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import io.aklivity.zilla.runtime.engine.config.KindConfig;
import org.agrona.IoUtil;
import org.agrona.LangUtil;
import org.agrona.collections.Long2LongHashMap;

public final class Tuning implements AutoCloseable
{
    private final int available;
    private final Long2LongHashMap affinities;
    private final Path tuning;
    private MappedByteBuffer mappedByteBuf;
    private int currentCapacity = 32 * 1024; // Increased to 32 KB to reduce resize frequency

    // Somewhere central (e.g., when resolving effective affinity per binding)
// Pseudocode – place near where you read from tuning and before dispatching
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    private static int normalizeAffinityForWindowsServer(int mask, KindConfig kind) {
        if (IS_WINDOWS && kind == KindConfig.SERVER) {
            // Pick the lowest set bit so exactly one worker gets it, e.g. 0b0001
            return Integer.lowestOneBit(mask);
        }
        return mask;
    }


    public Tuning(
            Path directory,
            int count)
    {
        this.available = (1 << count) - 1;
        this.affinities = new Long2LongHashMap(-1L);
        this.tuning = directory.resolve("tuning");
        System.out.printf("[%s] Initialized Tuning with directory: %s, count: %d, tuning file: %s, initial capacity: %d%n",
                System.currentTimeMillis(), directory, count, tuning, currentCapacity);
    }

    public void reset()
    {
        System.out.printf("[%s] Starting reset for tuning file: %s%n", System.currentTimeMillis(), tuning);
        try
        {
            // Explicitly unmap the buffer before attempting any file operations
            if (mappedByteBuf != null)
            {
                System.out.printf("[%s] Unmapping existing MappedByteBuffer%n", System.currentTimeMillis());
                IoUtil.unmap(mappedByteBuf);
                mappedByteBuf = null;
            }

            // Retry loop to handle transient file locks on Windows
            int maxAttempts = 10;
            int retryDelayMs = 500;
            for (int i = 0; i < maxAttempts; i++)
            {
                if (Files.exists(tuning))
                {
                    System.out.printf("[%s] Attempt %d to delete tuning file: %s%n",
                            System.currentTimeMillis(), i + 1, tuning);
                    try
                    {
                        Files.delete(tuning);
                        System.out.printf("[%s] Successfully deleted tuning file: %s%n",
                                System.currentTimeMillis(), tuning);
                        break;
                    }
                    catch (IOException e)
                    {
                        System.err.printf("[%s] Deletion attempt %d failed: %s%n",
                                System.currentTimeMillis(), i + 1, e.getMessage());
                        Thread.sleep(retryDelayMs);
                    }
                }
                else
                {
                    System.out.printf("[%s] Tuning file %s does not exist%n", System.currentTimeMillis(), tuning);
                    break;
                }
            }

            // Create parent directory
            System.out.printf("[%s] Creating parent directory: %s%n", System.currentTimeMillis(), tuning.getParent());
            Files.createDirectories(tuning.getParent());

            // Map the file initially (destructive init)
            System.out.printf("[%s] Mapping new tuning file: %s with capacity %d%n",
                    System.currentTimeMillis(), tuning, currentCapacity);
            mappedByteBuf = mapNewFile(tuning, currentCapacity);
            if (mappedByteBuf == null)
            {
                throw new IllegalStateException("Failed to map tuning file: " + tuning);
            }
            System.out.printf("[%s] Successfully mapped tuning file: %s%n", System.currentTimeMillis(), tuning);
        }
        catch (IOException | InterruptedException ex)
        {
            System.err.printf("[%s] Failed to reset tuning file %s: %s%n",
                    System.currentTimeMillis(), tuning, ex.getMessage());
            ex.printStackTrace(System.err);
            throw new RuntimeException("Error: Failed to reset tuning file " + tuning + ": " + ex.getMessage(), ex);
        }
    }

    public void affinity(
            long bindingId,
            long mask)
    {
        System.out.printf("[%s] Setting affinity for bindingId: %d, mask: %d%n", System.currentTimeMillis(), bindingId, mask);
        if (mappedByteBuf == null)
        {
            System.err.printf("[%s] Error: Tuning file not initialized: %s%n", System.currentTimeMillis(), tuning);
            throw new IllegalStateException("Tuning file not initialized: " + tuning);
        }

        assert (mask & ~available) == 0 || mask == 1L << (Long.SIZE - 1);

        long offset = affinities.get(bindingId);

        if (offset == affinities.missingValue())
        {
            System.out.printf("[%s] BindingId %d not found, appending to tuning file%n", System.currentTimeMillis(), bindingId);
            try (SeekableByteChannel channel = Files.newByteChannel(tuning, APPEND))
            {
                // Calculate new offset and check if resize is needed
                long newPosition = channel.position();
                offset = newPosition + Long.BYTES;
                long requiredSize = newPosition + 2 * Long.BYTES; // For bindingId + mask
                System.out.printf("[%s] Current file position: %d, new offset: %d, required size: %d%n",
                        System.currentTimeMillis(), newPosition, offset, requiredSize);
                resizeIfNecessary(requiredSize);

                ByteBuffer byteBuf = ByteBuffer
                        .wrap(new byte[Long.BYTES + Long.BYTES])
                        .order(nativeOrder());
                byteBuf.putLong(bindingId);
                byteBuf.putLong(0L);
                byteBuf.flip();

                System.out.printf("[%s] Writing bindingId %d at position %d%n", System.currentTimeMillis(), bindingId, offset);
                while (byteBuf.hasRemaining())
                {
                    channel.write(byteBuf);
                    Thread.onSpinWait();
                }

                affinities.put(bindingId, offset);
                System.out.printf("[%s] Stored offset %d for bindingId %d%n", System.currentTimeMillis(), offset, bindingId);
            }
            catch (IOException ex)
            {
                System.err.printf("[%s] Error writing to tuning file %s: %s%n", System.currentTimeMillis(), tuning, ex.getMessage());
                ex.printStackTrace(System.err);
                throw new RuntimeException("Error: " + tuning + " is not writeable", ex);
            }
        }

        // Ensure offset is within bounds for writing mask
        try
        {
            resizeIfNecessary(offset + Long.BYTES);
        }
        catch (IOException ex)
        {
            System.err.printf("[%s] Failed during resizeIfNecessary for bindingId %d: %s%n",
                    System.currentTimeMillis(), bindingId, ex.getMessage());
            ex.printStackTrace(System.err);
            throw new RuntimeException("Error resizing tuning file " + tuning, ex);
        }

        System.out.printf("[%s] Writing mask %d to offset %d (current capacity: %d)%n",
                System.currentTimeMillis(), mask, offset, currentCapacity);
        try
        {
            mappedByteBuf.putLong((int) offset, mask);
            System.out.printf("[%s] Successfully wrote mask for bindingId %d%n", System.currentTimeMillis(), bindingId);
        }
        catch (IndexOutOfBoundsException ex)
        {
            System.err.printf("[%s] IndexOutOfBoundsException at offset %d, capacity %d: %s%n",
                    System.currentTimeMillis(), offset, currentCapacity, ex.getMessage());
            ex.printStackTrace(System.err);
            throw ex;
        }
    }

    public long affinity(
            long bindingId) throws IOException {
        System.out.printf("[%s] Reading affinity for bindingId: %d%n", System.currentTimeMillis(), bindingId);
        long offset = affinities.get(bindingId);

        if (offset != affinities.missingValue())
        {
            // Ensure offset is within bounds for read
            resizeIfNecessary(offset + Long.BYTES);

            try
            {
                long mask = mappedByteBuf.getLong((int) offset);
                System.out.printf("[%s] Read mask %d for bindingId %d at offset %d%n",
                        System.currentTimeMillis(), mask, bindingId, offset);
                return mask;
            }
            catch (IndexOutOfBoundsException ex)
            {
                System.err.printf("[%s] IndexOutOfBoundsException reading offset %d, capacity %d: %s%n",
                        System.currentTimeMillis(), offset, currentCapacity, ex.getMessage());
                ex.printStackTrace(System.err);
                throw ex;
            }
        }
        System.out.printf("[%s] No affinity found for bindingId %d, returning available: %d%n",
                System.currentTimeMillis(), bindingId, available);
        return available;
    }

    @Override
    public void close() throws Exception
    {
        System.out.printf("[%s] Closing Tuning, unmapping file: %s%n", System.currentTimeMillis(), tuning);
        if (mappedByteBuf != null)
        {
            IoUtil.unmap(mappedByteBuf);
            mappedByteBuf = null;
            System.out.printf("[%s] Successfully unmapped tuning file: %s%n", System.currentTimeMillis(), tuning);
        }
    }

    private void resizeIfNecessary(long requiredSize) throws IOException
    {
        if (requiredSize > currentCapacity)
        {
            System.out.printf("[%s] Resizing buffer: current capacity %d, required size %d%n",
                    System.currentTimeMillis(), currentCapacity, requiredSize);
            if (mappedByteBuf != null)
            {
                System.out.printf("[%s] Unmapping current buffer before resize%n", System.currentTimeMillis());
                IoUtil.unmap(mappedByteBuf);
                mappedByteBuf = null;
            }

            // Double the capacity or use required size + buffer
            int newCapacity = Math.max(currentCapacity * 2, (int) requiredSize + 1024);
            System.out.printf("[%s] Calculated new capacity: %d%n", System.currentTimeMillis(), newCapacity);

            // Ensure the file size is sufficient
            try (FileChannel channel = FileChannel.open(tuning, WRITE))
            {
                System.out.printf("[%s] Attempting to extend file %s to size %d%n", System.currentTimeMillis(), tuning, newCapacity);
                try
                {
                    channel.truncate(newCapacity);
                    System.out.printf("[%s] Successfully extended file %s to size %d%n", System.currentTimeMillis(), tuning, newCapacity);
                }
                catch (IOException ex)
                {
                    System.err.printf("[%s] Failed to truncate file %s to size %d: %s%n",
                            System.currentTimeMillis(), tuning, newCapacity, ex.getMessage());
                    ex.printStackTrace(System.err);
                    throw ex;
                }
            }
            catch (IOException ex)
            {
                System.err.printf("[%s] Failed to open FileChannel for %s: %s%n", System.currentTimeMillis(), tuning, ex.getMessage());
                ex.printStackTrace(System.err);
                throw ex;
            }

            // Remap the file
            System.out.printf("[%s] Attempting to remap file %s with new capacity %d%n", System.currentTimeMillis(), tuning, newCapacity);
            try
            {
                mappedByteBuf = mapExistingFile(tuning, newCapacity);
                if (mappedByteBuf == null)
                {
                    System.err.printf("[%s] Failed to remap tuning file with new capacity: %d%n", System.currentTimeMillis(), newCapacity);
                    throw new IOException("Failed to remap tuning file with new capacity: " + newCapacity);
                }
                currentCapacity = newCapacity;
                System.out.printf("[%s] Successfully resized and remapped tuning file to capacity %d%n",
                        System.currentTimeMillis(), currentCapacity);
            }
            catch (IOException ex)
            {
                System.err.printf("[%s] Failed to remap file %s with capacity %d: %s%n",
                        System.currentTimeMillis(), tuning, newCapacity, ex.getMessage());
                ex.printStackTrace(System.err);
                throw ex;
            }
        }
        else
        {
            System.out.printf("[%s] No resize needed: required size %d <= current capacity %d%n",
                    System.currentTimeMillis(), requiredSize, currentCapacity);
        }
    }

    private static MappedByteBuffer mapExistingFile(Path file, int capacity) throws IOException
    {
        System.out.printf("[%s] Remapping existing tuning file: %s with capacity %d%n",
                System.currentTimeMillis(), file, capacity);

        try (FileChannel channel = FileChannel.open(file, CREATE, READ, WRITE))
        {
            long size = channel.size();
            if (size < capacity)
            {
                System.out.printf("[%s] Extending file %s from %d to %d bytes%n",
                        System.currentTimeMillis(), file, size, capacity);
                channel.truncate(capacity);
            }

            MappedByteBuffer mapped = channel.map(MapMode.READ_WRITE, 0, capacity);
            mapped.order(nativeOrder());
            System.out.printf("[%s] Existing tuning file remapped with capacity %d%n", System.currentTimeMillis(), capacity);
            return mapped;
        }
    }


    private static MappedByteBuffer mapNewFile(Path file, int capacity) throws IOException
    {
        System.out.printf("[%s] Creating new tuning file: %s with capacity %d%n",
                System.currentTimeMillis(), file, capacity);

        try (FileChannel channel = FileChannel.open(file, CREATE, TRUNCATE_EXISTING, READ, WRITE))
        {
            channel.truncate(capacity);
            MappedByteBuffer mapped = channel.map(MapMode.READ_WRITE, 0, capacity);
            mapped.order(nativeOrder());
            System.out.printf("[%s] New tuning file mapped at capacity %d%n", System.currentTimeMillis(), capacity);
            return mapped;
        }
    }

}