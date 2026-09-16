package net.minestom.server.network.packet.server;

import net.minestom.server.network.packet.PacketEncodingContext;
import net.minestom.server.network.player.PlayerConnection;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a packet that can be sent to a {@link PlayerConnection}.
 */
public sealed interface SendablePacket
        permits BufferedPacket, CachedPacket, FramedPacket, ServerPacket {

    static @Nullable ServerPacket extractServerPacket(PacketEncodingContext context, SendablePacket packet) {
        return switch (packet) {
            case ServerPacket serverPacket -> serverPacket;
            case CachedPacket cachedPacket -> cachedPacket.packet(context);
            case FramedPacket framedPacket -> framedPacket.packet();
            case BufferedPacket _ -> null;
        };
    }
}
