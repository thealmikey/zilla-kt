package io.aklivity.zilla.runtime.binding.tcp.internal.stream;

import static io.aklivity.zilla.runtime.engine.config.KindConfig.SERVER;
import static java.nio.channels.SelectionKey.OP_ACCEPT;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.agrona.CloseHelper;

import io.aklivity.zilla.runtime.binding.tcp.internal.TcpCapacityTracker;
import io.aklivity.zilla.runtime.binding.tcp.internal.config.TcpBindingConfig;
import io.aklivity.zilla.runtime.binding.tcp.internal.config.TcpServerBindingConfig;
import io.aklivity.zilla.runtime.engine.EngineContext;
import io.aklivity.zilla.runtime.engine.poller.PollerKey;

public final class TcpServerRouter
{
    private final ToIntFunction<PollerKey> acceptHandler;
    private final Function<SelectableChannel, PollerKey> supplyPollerKey;
    private final TcpCapacityTracker capacity;
    private final ConcurrentHashMap<Long, TcpBindingConfig> bindings;
    private final ConcurrentHashMap<Long, TcpServerBindingConfig> serversById;

    private boolean unbound;

    public TcpServerRouter(
            EngineContext context,
            ToIntFunction<PollerKey> acceptHandler,
            TcpCapacityTracker capacity)
    {
        this.bindings = new ConcurrentHashMap<>();
        this.serversById = new ConcurrentHashMap<>();
        this.supplyPollerKey = context::supplyPollerKey;
        this.acceptHandler = acceptHandler;
        this.capacity = capacity;
    }

    public void attach(
            TcpBindingConfig binding)
    {
        System.out.printf("[%s]Fix Updated for grok to see: Attaching bindingId %d in TcpServerRouter%n", System.currentTimeMillis(), binding.id);
        if (bindings.putIfAbsent(binding.id, binding) != null) {
            System.out.printf("[%s]Fix Updated for grok to see in binding check: Skipping duplicate attach for bindingId %d%n", System.currentTimeMillis(), binding.id);
            return;
        }
        register(binding);
    }

    public TcpBindingConfig resolve(
            long bindingId,
            long authorization)
    {
        return bindings.get(bindingId);
    }

    public void detach(
            long bindingId)
    {
        System.out.printf("[%s] Detaching bindingId %d in TcpServerRouter%n", System.currentTimeMillis(), bindingId);
        TcpBindingConfig binding = bindings.remove(bindingId);
        if (binding != null) {
            unregister(binding);
        }
    }

    public SocketChannel accept(
            ServerSocketChannel server) throws IOException
    {
        SocketChannel channel = null;

        if (capacity.get() > 0)
        {
            channel = server.accept();

            if (channel != null)
            {
                capacity.decrementAndGet();
            }
        }

        if (!unbound && capacity.get() <= 0)
        {
            bindings.values().stream()
                    .filter(b -> b.kind == SERVER)
                    .forEach(this::unregister);
            unbound = true;
        }

        return channel;
    }

    public void close(
            SocketChannel channel)
    {
        CloseHelper.quietClose(channel);

        int newCapacity = capacity.incrementAndGet();
        if (unbound && newCapacity > 0)
        {
            bindings.values().stream()
                    .filter(b -> b.kind == SERVER)
                    .forEach(this::register);
            unbound = false;
        }
    }

    private void register(TcpBindingConfig binding)
    {
        System.out.printf("[%s] Registering bindingId %d in TcpServerRouter%n",
                System.currentTimeMillis(), binding.id);
        System.out.printf(
                "[%s] [TcpServerRouter] Worker %s: attempting to register server bindingId=%d (%s:%s)%n",
                System.currentTimeMillis(),
                Thread.currentThread().getName(),
                binding.id,
                binding.options.host,
                Arrays.toString(binding.options.ports));

        // Try to reuse existing bound channels (first worker wins, others reuse)
        ServerSocketChannel[] channels = TcpServerBindRegistry.get(binding.id);
        if (channels == null)
        {
            TcpServerBindingConfig server = serversById.computeIfAbsent(binding.id, TcpServerBindingConfig::new);
            ServerSocketChannel[] freshlyBound = server.bind(binding.options);

            // Publish once; if someone else beat us, close ours and reuse theirs
            ServerSocketChannel[] existing = TcpServerBindRegistry.putIfAbsent(binding.id, freshlyBound);
            if (existing != null) {
                // Another worker bound first; close ours and reuse theirs
                for (ServerSocketChannel ch : freshlyBound) {
                    try { ch.close(); } catch (Exception ignore) { }
                }
                channels = existing;
            } else {
                channels = freshlyBound;
            }
        }

        // Now (channels) is the single, JVM-wide bound array. Register with this worker’s poller.
        PollerKey[] acceptKeys = new PollerKey[channels.length];
        for (int i = 0; i < channels.length; i++)
        {
            acceptKeys[i] = supplyPollerKey.apply(channels[i]);
            acceptKeys[i].handler(OP_ACCEPT, acceptHandler);
            acceptKeys[i].register(OP_ACCEPT);
            acceptKeys[i].attach(binding);
        }

        binding.attach(acceptKeys);
        System.out.printf(
                "[%s] [TcpServerRouter] Worker %s: successfully registered bindingId=%d (%s:%s)%n",
                System.currentTimeMillis(),
                Thread.currentThread().getName(),
                binding.id,
                binding.options.host,
                Arrays.toString(binding.options.ports));
    }


    private void unregister(
            TcpBindingConfig binding)
    {
        System.out.printf("[%s] Unregistering bindingId %d in TcpServerRouter%n", System.currentTimeMillis(), binding.id);
        System.out.printf(
                "[%s] [TcpServerRouter] Worker %s: unregistering bindingId=%d (%s:%s)%n",
                System.currentTimeMillis(),
                Thread.currentThread().getName(),
                binding.id,
                binding.options.host,
                Arrays.toString(binding.options.ports));
        PollerKey[] acceptKeys = binding.attach(null);
        if (acceptKeys != null)
        {
            for (PollerKey acceptKey : acceptKeys)
            {
                acceptKey.cancel();
            }
        }

        TcpServerBindingConfig server = serversById.get(binding.id);
        server.unbind();
    }
}