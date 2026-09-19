package net.minestom.server.network.packet;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.property.ServerProperties;
import net.minestom.server.registry.Registries;
import net.minestom.server.utils.ObjectPool;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/** Registry-bound buffers owned by one process. Borrowers must return buffers, including on failure. */
@ApiStatus.Internal
public final class PacketBufferPool implements AutoCloseable {
    private final Registries registries;
    private final ServerProperties properties;
    private final ObjectPool<NetworkBuffer> pool;
    private volatile boolean closed;

    public PacketBufferPool(Registries registries) {
        this(registries, ServerProperties.fromSystemProperties());
    }

    public PacketBufferPool(Registries registries, ServerProperties properties) {
        this.registries = Objects.requireNonNull(registries);
        this.properties = Objects.requireNonNull(properties);
        this.pool = ObjectPool.pool(() -> NetworkBuffer.staticBuffer(properties.pooledBufferSize().get(), registries, properties),
                NetworkBuffer::clear);
    }

    public ServerProperties properties() {
        return properties;
    }

    public Registries registries() {
        return registries;
    }

    public PacketEncodingContext context(ConnectionState state, int compressionThreshold) {
        return new PacketEncodingContext(this, state, Math.max(0, compressionThreshold));
    }

    public NetworkBuffer get() {
        // Connection writers can still drain their final packets after process shutdown.
        return closed ? NetworkBuffer.staticBuffer(properties.pooledBufferSize().get(), registries, properties) : pool.get();
    }

    public void add(NetworkBuffer buffer) {
        if (buffer.registries() != registries || buffer.properties() != properties || buffer.isReadOnly())
            throw new IllegalArgumentException("Buffer does not belong to this registry and property context");
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
