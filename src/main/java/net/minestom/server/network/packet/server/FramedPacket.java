package net.minestom.server.network.packet.server;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketEncodingContext;
import org.jetbrains.annotations.ApiStatus;

/**
 * Represents a packet which is already framed. (packet id+payload) + optional compression
 * Can be used if you want to send the exact same buffer to multiple clients without processing it more than once.
 */
@ApiStatus.Internal
public record FramedPacket(PacketEncodingContext context,
                           ServerPacket packet,
                           NetworkBuffer body) implements SendablePacket {
    public FramedPacket {
        if (body.registries() != context.registries() || body.properties() != context.properties())
            throw new IllegalArgumentException("Foreign buffer registries or properties");
        body = body.readOnly().readIndex(0);
    }
}
