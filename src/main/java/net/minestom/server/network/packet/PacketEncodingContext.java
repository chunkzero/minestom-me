package net.minestom.server.network.packet;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * The owner, protocol state, and negotiated compression used to encode a representation.
 *
 * @param buffers the registry-bound pool owned by the sender
 * @param state the protocol state used for this representation
 * @param compressionThreshold the negotiated threshold, or zero before negotiation or when disabled
 */
@ApiStatus.Internal
public record PacketEncodingContext(PacketBufferPool buffers, ConnectionState state, int compressionThreshold) {
    public PacketEncodingContext {
        Objects.requireNonNull(buffers);
        Objects.requireNonNull(state);
        if (compressionThreshold < 0) throw new IllegalArgumentException("Negative compression threshold");
    }

    public Registries registries() {
        return buffers.registries();
    }

    public void write(NetworkBuffer buffer, ServerPacket packet) {
        if (buffer.registries() != registries()) throw new IllegalArgumentException("Foreign buffer registries");
        @SuppressWarnings("unchecked") // The packet must be valid for this protocol state.
        var registry = (PacketRegistry<ServerPacket>) PacketVanilla.SERVER_PACKET_PARSER.stateRegistry(state);
        var info = registry.packetInfo(packet);
        PacketWriting.writeFramedPacket(buffer, info.serializer(), info.id(), packet, compressionThreshold, buffers);
    }

    public NetworkBuffer frame(ServerPacket packet) {
        return PacketWriting.allocateTrimmedPacket(this, packet);
    }
}
