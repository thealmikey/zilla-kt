package io.aklivity.zilla.runtime.binding.tcp.internal.config;

import static java.net.StandardSocketOptions.SO_REUSEADDR;
import static java.net.StandardSocketOptions.SO_REUSEPORT;
import static org.agrona.CloseHelper.quietClose;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.aklivity.zilla.runtime.binding.tcp.config.TcpOptionsConfig;
import org.agrona.LangUtil;

public final class TcpServerBindingConfig
{
    public final long id;

    private final Lock lock = new ReentrantLock();
    private final AtomicInteger binds;
    private volatile ServerSocketChannel[] channels;
    private volatile boolean bindingInProgress;

    public TcpServerBindingConfig(
            long bindingId)
    {
        this.id = bindingId;
        this.binds = new AtomicInteger();
        this.bindingInProgress = false;
    }

    public synchronized ServerSocketChannel[] bind(
            TcpOptionsConfig options)
    {
        System.out.printf("[%s] Attempting to bind TCP server for bindingId %d on %s:%s, binds=%d, channels=%s, bindingInProgress=%b%n",
                System.currentTimeMillis(), id, options.host, options.ports != null ? java.util.Arrays.toString(options.ports) : "[]",
                binds.get(), channels != null ? "non-null" : "null", bindingInProgress);

        // Check if binding is already done
        if (channels != null)
        {
            System.out.printf("[%s] BindingId %d already bound, returning existing channels%n",
                    System.currentTimeMillis(), id);
            binds.incrementAndGet(); // Increment binds to reflect usage
            return channels;
        }

        // Check if binding is in progress
        if (bindingInProgress)
        {
            System.out.printf("[%s] BindingId %d binding in progress, waiting for completion%n",
                    System.currentTimeMillis(), id);
            while (bindingInProgress && channels == null)
            {
                try
                {
                    Thread.sleep(10);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    System.err.printf("[%s] Interrupted while waiting for bindingId %d%n",
                            System.currentTimeMillis(), id);
                }
            }
            System.out.printf("[%s] BindingId %d wait complete, channels=%s%n",
                    System.currentTimeMillis(), id, channels != null ? "non-null" : "null");
            if (channels != null)
            {
                binds.incrementAndGet(); // Increment binds to reflect usage
            }
            return channels != null ? channels : new ServerSocketChannel[0];
        }

        bindingInProgress = true;
        try
        {
            lock.lock();

            // Double-check binds to prevent race
            if (binds.get() > 0 && channels != null)
            {
                System.out.printf("[%s] BindingId %d already bound after lock, returning existing channels%n",
                        System.currentTimeMillis(), id);
                binds.incrementAndGet(); // Increment binds to reflect usage
                return channels;
            }

            int bindCount = binds.getAndIncrement();
            System.out.printf("[%s] BindingId %d starting bind, bindCount=%d%n",
                    System.currentTimeMillis(), id, bindCount);

            int size = options.ports != null ? options.ports.length : 0;
            ServerSocketChannel[] newChannels = new ServerSocketChannel[size];

            for (int i = 0; i < size; i++)
            {
                ServerSocketChannel channel = ServerSocketChannel.open();
                System.out.printf("[%s] Configuring TCP server socket for bindingId %d on %s:%d%n",
                        System.currentTimeMillis(), id, options.host, options.ports[i]);

                InetAddress address = InetAddress.getByName(options.host);
                InetSocketAddress local = new InetSocketAddress(address, options.ports[i]);

                channel.setOption(SO_REUSEADDR, true);
                String osName = System.getProperty("os.name").toLowerCase();
                if (!osName.contains("win"))
                {
                    System.out.printf("[%s] Non-Windows OS detected, setting SO_REUSEPORT for %s:%d%n",
                            System.currentTimeMillis(), options.host, options.ports[i]);
                    try
                    {
                        channel.setOption(SO_REUSEPORT, true);
                    }
                    catch (UnsupportedOperationException ex)
                    {
                        System.err.printf("[%s] SO_REUSEPORT not supported, continuing with SO_REUSEADDR only: %s%n",
                                System.currentTimeMillis(), ex.getMessage());
                    }
                }
                else
                {
                    System.out.printf("[%s] Windows detected, skipping SO_REUSEPORT for %s:%d%n",
                            System.currentTimeMillis(), options.host, options.ports[i]);
                }

                try
                {
                    channel.bind(local, options.backlog);
                    channel.configureBlocking(false);
                    System.out.printf("[%s] Successfully bound TCP server socket to %s:%d%n",
                            System.currentTimeMillis(), options.host, options.ports[i]);
                    newChannels[i] = channel;
                }
                catch (IOException ex)
                {
                    System.err.printf("[%s] Failed to bind TCP server socket for bindingId %d on %s:%d: %s%n",
                            System.currentTimeMillis(), id, options.host, options.ports[i], ex.getMessage());
                    quietClose(channel);
                    for (int j = 0; j < i; j++)
                    {
                        quietClose(newChannels[j]);
                    }
                    binds.decrementAndGet(); // Revert binds count on failure
                    throw ex;
                }
            }

            channels = newChannels;
            System.out.printf("[%s] BindingId %d completed binding, channels assigned%n",
                    System.currentTimeMillis(), id);
        }
        catch (IOException ex)
        {
            System.err.printf("[%s] Failed to bind TCP server socket for bindingId %d: %s%n",
                    System.currentTimeMillis(), id, ex.getMessage());
            ex.printStackTrace(System.err);
            LangUtil.rethrowUnchecked(ex);
        }
        finally
        {
            bindingInProgress = false;
            lock.unlock();
        }

        return channels;
    }

    public void unbind()
    {
        try
        {
            lock.lock();
            int bindCount = binds.get();
            System.out.printf("[%s] Unbinding bindingId %d, binds=%d, channels=%s%n",
                    System.currentTimeMillis(), id, bindCount, channels != null ? "non-null" : "null");

            if (bindCount <= 0)
            {
                System.out.printf("[%s] Skipping unbind for bindingId %d, binds=%d%n",
                        System.currentTimeMillis(), id, bindCount);
                return;
            }

            bindCount = binds.decrementAndGet();
            if (bindCount == 0 && channels != null)
            {
                for (ServerSocketChannel channel : channels)
                {
                    System.out.printf("[%s] Closing TCP server socket for bindingId %d%n",
                            System.currentTimeMillis(), id);
                    quietClose(channel);
                }
                channels = null;
            }
            else if (bindCount < 0)
            {
                System.err.printf("[%s] Warning: binds count for bindingId %d is negative (%d)%n",
                        System.currentTimeMillis(), id, bindCount);
                binds.set(0); // Reset to avoid negative counts
            }
        }
        finally
        {
            lock.unlock();
        }
    }
}