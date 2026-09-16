package net.minestom.server.network.packet;

import net.minestom.server.ServerFlag;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.registry.Registries;
import net.minestom.server.utils.ObjectPool;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Registry-bound buffers owned by one process. Borrowers must return buffers, including on failure. */
@ApiStatus.Internal
public final class PacketBufferPool implements AutoCloseable {
    private final Registries registries;
    private final ObjectPool<NetworkBuffer> pool;
    private boolean closed;

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

    public synchronized NetworkBuffer get() {
        if (closed) throw new RejectedExecutionException("Packet buffer pool is closed");
        return pool.get();
    }

    public synchronized void add(NetworkBuffer buffer) {
        if (buffer.registries() != registries || buffer.isReadOnly())
            throw new IllegalArgumentException("Buffer does not belong to this registry context");
        if (!closed) pool.add(buffer);
    }

    @Override
    public synchronized void close() {
        closed = true;
        pool.clear();
    }
}
