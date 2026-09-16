package net.minestom.server.network.packet.server;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketEncodingContext;
import org.jetbrains.annotations.ApiStatus;

/**
 * Represents a buffer to directly write to the network.
 * <p>
 * May contain multiple packets.
 */
@ApiStatus.Internal
public record BufferedPacket(PacketEncodingContext context,
                             NetworkBuffer buffer,
                             long index, long length) implements SendablePacket {
    public BufferedPacket {
        if (buffer.registries() != context.registries()) throw new IllegalArgumentException("Foreign buffer registries");
        if (index < 0 || length < 0 || index > buffer.writeIndex() - length)
            throw new IndexOutOfBoundsException("Invalid packet range");
        buffer = buffer.readOnly();
    }
}
