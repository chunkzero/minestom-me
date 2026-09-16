package net.minestom.server.utils;

import net.minestom.server.ServerFlag;
import net.minestom.server.ServerProcess;
import net.minestom.server.Viewable;
import net.minestom.server.entity.Entity;
import net.minestom.server.network.packet.server.ServerPacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class PacketViewableUtils {
    public static void prepareViewablePacket(ServerProcess process, Viewable viewable, ServerPacket packet,
                                             @Nullable Entity entity) {
        if (entity != null && entity.process() != process) throw new IllegalArgumentException("Foreign entity");
        if (entity != null && !entity.hasPredictableViewers()) {
            entity.sendPacketToViewers(packet);
        } else if (!ServerFlag.VIEWABLE_PACKET) {
            PacketSendingUtils.sendGroupedPacket(viewable.getViewers(), packet, player -> player != entity);
        } else {
            process.packetBatcher().prepareViewablePacket(viewable, packet, entity);
        }
    }

    public static void prepareViewablePacket(ServerProcess process, Viewable viewable, ServerPacket packet) {
        prepareViewablePacket(process, viewable, packet, null);
    }
}
