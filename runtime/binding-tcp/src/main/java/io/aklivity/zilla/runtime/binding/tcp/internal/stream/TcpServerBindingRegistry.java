package io.aklivity.zilla.runtime.binding.tcp.internal.stream;

import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ConcurrentHashMap;

final class TcpServerBindRegistry {
    private static final ConcurrentHashMap<Long, ServerSocketChannel[]> CHANNELS = new ConcurrentHashMap<>();

    static ServerSocketChannel[] get(long bindingId) {
        return CHANNELS.get(bindingId);
    }

    static ServerSocketChannel[] putIfAbsent(long bindingId, ServerSocketChannel[] channels) {
        return CHANNELS.putIfAbsent(bindingId, channels);
    }

    private TcpServerBindRegistry() { }
}
