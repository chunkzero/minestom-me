package net.minestom.server.network.packet;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import org.jctools.queues.MessagePassingQueue;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiPredicate;

/**
 * Tools to write packets into a {@link NetworkBuffer} for network processing.
 * <p>
 * Fairly internal and performance sensitive.
 */
@ApiStatus.Internal
public final class PacketWriting {
    public static void writeFramedPacket(NetworkBuffer buffer,
                                         ConnectionState state,
                                         ClientPacket packet,
                                         int compressionThreshold) throws IndexOutOfBoundsException {
        writeFramedPacket(buffer, PacketVanilla.CLIENT_PACKET_PARSER, state, packet, compressionThreshold);
    }

    public static void writeFramedPacket(NetworkBuffer buffer,
                                         ConnectionState state,
                                         ServerPacket packet,
                                         int compressionThreshold) throws IndexOutOfBoundsException {
        writeFramedPacket(buffer, PacketVanilla.SERVER_PACKET_PARSER, state, packet, compressionThreshold);
    }

    public static <T> void writeFramedPacket(NetworkBuffer buffer,
                                             PacketParser<? super T> parser,
                                             ConnectionState state,
                                             T packet,
                                             int compressionThreshold) throws IndexOutOfBoundsException {
        @SuppressWarnings("unchecked") // We assume ConnectionState and PacketRegistry are in sync
        final PacketRegistry<? super T> registry = (PacketRegistry<? super T>) parser.stateRegistry(state);
        writeFramedPacket(buffer, registry, packet, compressionThreshold);
    }

    public static <T> void writeFramedPacket(NetworkBuffer buffer,
                                             PacketRegistry<? super T> registry,
                                             T packet,
                                             int compressionThreshold) throws IndexOutOfBoundsException {
        final PacketRegistry.PacketInfo<? super T> packetInfo = registry.packetInfo(packet);
        writeFramedPacket(
                buffer,
                packetInfo, packet,
                compressionThreshold
        );
    }

    public static <T> void writeFramedPacket(NetworkBuffer buffer,
                                             PacketRegistry.PacketInfo<? super T> packetInfo,
                                             T packet,
                                             int compressionThreshold) throws IndexOutOfBoundsException {
        final int id = packetInfo.id();
        final NetworkBuffer.Type<? super T> serializer = packetInfo.serializer();
        writeFramedPacket(
                buffer, serializer,
                id, packet,
                compressionThreshold
        );
    }

    public static <T> void writeFramedPacket(NetworkBuffer buffer,
                                             NetworkBuffer.Type<? super T> type,
                                             int id, T packet,
                                             int compressionThreshold) throws IndexOutOfBoundsException {
        writeFramedPacket(buffer, type, id, packet, compressionThreshold, null);
    }

    static <T> void writeFramedPacket(NetworkBuffer buffer, NetworkBuffer.Type<? super T> type,
                                    int id, T packet, int compressionThreshold, @Nullable PacketBufferPool pool) {
        if (compressionThreshold <= 0) writeUncompressedFormat(buffer, type, id, packet);
        else writeCompressedFormat(buffer, type, id, packet, compressionThreshold, pool);
    }

    private static <T> void writeUncompressedFormat(NetworkBuffer buffer,
                                                    NetworkBuffer.Type<? super T> type,
                                                    int id, T packet) throws IndexOutOfBoundsException {
        // Uncompressed format https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol#Without_compression
        final long lengthIndex = buffer.advanceWrite(3);
        buffer.write(NetworkBuffer.VAR_INT, id);
        buffer.write(type, packet);
        final long finalSize = buffer.writeIndex() - (lengthIndex + 3);
        checkPacketSize(buffer, finalSize);
        buffer.writeAt(lengthIndex, NetworkBuffer.VAR_INT_3, (int) finalSize);
    }

    private static <T> void writeCompressedFormat(NetworkBuffer buffer,
                                                  NetworkBuffer.Type<? super T> type,
                                                  int id, T packet,
                                                  int compressionThreshold, @Nullable PacketBufferPool pool) throws IndexOutOfBoundsException {
        // Compressed format https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol#With_compression
        final long compressedIndex = buffer.advanceWrite(3);
        final long uncompressedIndex = buffer.advanceWrite(3);
        final long contentStart = buffer.writeIndex();
        buffer.write(NetworkBuffer.VAR_INT, id);
        buffer.write(type, packet);
        final long packetSize = buffer.writeIndex() - contentStart;
        checkPacketSize(buffer, packetSize);
        final boolean compressed = packetSize >= compressionThreshold;
        if (compressed) {
            // Write the compressed content into the pooled buffer
            // and compress it into the current buffer
            NetworkBuffer input = pool != null ? pool.get() : NetworkBuffer.staticBuffer(packetSize, buffer.registries(), buffer.properties());
            try {
                if (input.capacity() < packetSize) input.resize(packetSize);
                NetworkBuffer.copy(buffer, contentStart, input, 0, packetSize);
                buffer.writeIndex(contentStart);
                input.compress(0, packetSize, buffer);
            } finally {
                if (pool != null) pool.add(input);
            }
        }
        // Packet header (Packet + Data Length)
        checkPacketSize(buffer, buffer.writeIndex() - uncompressedIndex);
        buffer.writeAt(compressedIndex, NetworkBuffer.VAR_INT_3, (int) (buffer.writeIndex() - uncompressedIndex));
        buffer.writeAt(uncompressedIndex, NetworkBuffer.VAR_INT_3, compressed ? (int) packetSize : 0);
    }

    private static void checkPacketSize(NetworkBuffer buffer, long size) {
        if (size > buffer.properties().maxPacketSize().get())
            throw new IllegalStateException("Packet too large: " + size);
    }

    public static <T> NetworkBuffer allocateTrimmedPacket(
            NetworkBuffer tmpBuffer,
            PacketParser<? super T> parser,
            ConnectionState state,
            T packet,
            int compressionThreshold) {
        @SuppressWarnings("unchecked") // We assume ConnectionState and PacketRegistry are in sync
        final PacketRegistry<? super T> registry = (PacketRegistry<? super T>) parser.stateRegistry(state);
        return allocateTrimmedPacket(tmpBuffer, registry, packet, compressionThreshold);
    }

    public static NetworkBuffer allocateTrimmedPacket(PacketEncodingContext context, ServerPacket packet) {
        NetworkBuffer buffer = context.buffers().get();
        try {
            @SuppressWarnings("unchecked") // The packet must be valid for this protocol state.
            var registry = (PacketRegistry<ServerPacket>) PacketVanilla.SERVER_PACKET_PARSER.stateRegistry(context.state());
            return allocateTrimmedPacket(buffer, registry, packet, context.compressionThreshold(), context.buffers());
        } finally {
            context.buffers().add(buffer);
        }
    }

    public static <T> NetworkBuffer allocateTrimmedPacket(
            NetworkBuffer tmpBuffer,
            PacketRegistry<? super T> registry,
            T packet,
            int compressionThreshold) {
        return allocateTrimmedPacket(tmpBuffer, registry, packet, compressionThreshold, null);
    }

    private static <T> NetworkBuffer allocateTrimmedPacket(NetworkBuffer tmpBuffer, PacketRegistry<? super T> registry,
                                                          T packet, int compressionThreshold, @Nullable PacketBufferPool pool) {
        final PacketRegistry.PacketInfo<? super T> packetInfo = registry.packetInfo(packet);
        final int id = packetInfo.id();
        final NetworkBuffer.Type<? super T> serializer = packetInfo.serializer();
        while (true) {
            try {
                writeFramedPacket(tmpBuffer, serializer, id, packet, compressionThreshold, pool);
                return tmpBuffer.copy(0, tmpBuffer.writeIndex());
            } catch (IndexOutOfBoundsException _) {
                final long sizeOf = serializer.sizeOf(packet, tmpBuffer.registries(), tmpBuffer.properties());
                // Leave room for the three framing varints, and retry if compression expands the payload.
                final long maxCapacity = tmpBuffer.properties().maxPacketSize().get() + 15L;
                if (sizeOf > tmpBuffer.properties().maxPacketSize().get() || tmpBuffer.capacity() >= maxCapacity) {
                    throw new IllegalStateException("Packet too large: " + sizeOf);
                }
                tmpBuffer.resize(Math.min(maxCapacity, Math.max(sizeOf + 15, tmpBuffer.capacity() * 2)));
                tmpBuffer.writeIndex(0);
            }
        }
    }

    public static <T> void writeQueue(NetworkBuffer buffer, MessagePassingQueue<T> queue, int minWrite,
                                      BiPredicate<NetworkBuffer, T> writer) {
        // The goal of this method is to write at the very least `minWrite` packets if the queue permits it.
        // The buffer is resized if it cannot hold this minimum.
        final int size = queue.size();
        minWrite = Math.min(minWrite, size);
        T packet;
        int written = 0;
        while ((packet = queue.peek()) != null) {
            final long index = buffer.writeIndex();
            boolean success;
            try {
                success = writer.test(buffer, packet);
            } catch (IndexOutOfBoundsException _) {
                success = false;
            }
            // Consumed packets may write no bytes when cancelled or when a batch has become stale.
            if (success) {
                // Packet consumed
                queue.poll();
                written++;
            } else {
                buffer.writeIndex(index);
                if (written < minWrite) {
                    // Try again with a bigger buffer
                    final long newSize = Math.min(buffer.capacity() * 2, buffer.properties().maxPacketSize().get() + 15L);
                    if (newSize <= buffer.capacity()) break; // We reached the maximum size
                    buffer.resize(newSize);
                } else {
                    // At least one packet has been written
                    // Not worth resizing to fit more, we'll try again next flush
                    break;
                }
            }
        }
    }
}
