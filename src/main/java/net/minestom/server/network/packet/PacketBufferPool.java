package net.minestom.server.network.packet;

import net.minestom.server.ServerFlag;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.registry.Registries;
import net.minestom.server.utils.ObjectPool;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/** Registry-bound buffers owned by one process. Borrowers must return buffers, including on failure. */
@ApiStatus.Internal
public final class PacketBufferPool implements AutoCloseable {
    private final Registries registries;
    private final ObjectPool<NetworkBuffer> pool;
    private volatile boolean closed;

    public PacketBufferPool(Registries registries) {
        this.registries = Objects.requireNonNull(registries);
        this.pool = ObjectPool.pool(() -> NetworkBuffer.staticBuffer(ServerFlag.POOLED_BUFFER_SIZE, registries),
                NetworkBuffer::clear);
    }

    public Registries registries() {
        return registries;
    }

    public PacketEncodingContext context(ConnectionState state, int compressionThreshold) {
        return new PacketEncodingContext(this, state, Math.max(0, compressionThreshold));
    }

    public NetworkBuffer get() {
        // Connection writers can still drain their final packets after process shutdown.
        return closed ? NetworkBuffer.staticBuffer(ServerFlag.POOLED_BUFFER_SIZE, registries) : pool.get();
    }

    public void add(NetworkBuffer buffer) {
        if (buffer.registries() != registries || buffer.isReadOnly())
            throw new IllegalArgumentException("Buffer does not belong to this registry context");
        if (closed) return;
        pool.add(buffer);
        if (closed) pool.clear();
    }

    @Override
    public void close() {
        closed = true;
        pool.clear();
    }
}
