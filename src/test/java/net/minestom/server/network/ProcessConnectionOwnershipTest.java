package net.minestom.server.network;

import net.minestom.server.ServerProcess;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.configuration.SelectKnownPacksPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessConnectionOwnershipTest {
    @Test
    void foreignConnectionsAndPlayersAreRejectedBeforeAdmission() {
        try (var pair = new ServerProcessPair()) {
            var firstConnection = new ImmediateConnection(pair.first());
            var secondConnection = new ImmediateConnection(pair.second());
            var profile = new GameProfile(UUID.randomUUID(), "Player");
            var player = pair.first().connection().createPlayer(firstConnection, profile);
            assertThrows(IllegalArgumentException.class, () -> pair.second().connection().createPlayer(firstConnection, profile));
            assertThrows(IllegalArgumentException.class, () -> pair.second().connection().transitionLoginToConfig(firstConnection, profile));
            assertThrows(IllegalArgumentException.class, () -> pair.second().connection().doConfiguration(player, true));
            assertThrows(IllegalArgumentException.class, () -> pair.second().connection().transitionConfigToPlay(player));
            assertThrows(IllegalArgumentException.class, () -> pair.second().connection().transitionPlayToConfig(player));
            assertThrows(IllegalArgumentException.class, () -> pair.second().connection().removePlayer(firstConnection));
            assertThrows(IllegalArgumentException.class, () -> secondConnection.setPlayer(player));
            assertThrows(IllegalArgumentException.class, () -> player.setPendingOptions(pair.second().instance().createInstanceContainer(), false));
            assertNull(secondConnection.getPlayer());
            assertTrue(pair.second().connection().getConfigPlayers().isEmpty());
            assertTrue(pair.second().connection().getOnlinePlayers().isEmpty());
            assertSame(player, firstConnection.getPlayer());
        }
    }

    @Test
    void providerCannotReturnAPlayerForAnotherConnectionEvenWithinOneProcess() {
        try (var pair = new ServerProcessPair()) {
            var first = new ImmediateConnection(pair.first());
            var other = new ImmediateConnection(pair.first());
            var profile = new GameProfile(UUID.randomUUID(), "Player");
            var player = pair.first().connection().createPlayer(first, profile);
            pair.first().connection().setPlayerProvider((_, _) -> player);
            assertThrows(IllegalArgumentException.class, () -> pair.first().connection().createPlayer(other, profile));
            assertThrows(IllegalArgumentException.class, () -> other.setPlayer(player));
            assertNull(pair.first().connection().getPlayer(other));
            assertNull(other.getPlayer());
            assertSame(player, pair.first().connection().getPlayer(first));
        }
    }

    @Test
    void listenerManagerRejectsForeignConnectionsBeforeChangingState() {
        try (var pair = new ServerProcessPair()) {
            var connection = new ImmediateConnection(pair.first());
            var packet = new ClientHandshakePacket(0, "localhost", 25565, ClientHandshakePacket.Intent.STATUS);
            assertThrows(IllegalArgumentException.class, () -> pair.second().packetListener().processClientPacket(packet, connection));
            assertEquals(ConnectionState.HANDSHAKE, connection.getClientState());
            assertEquals(ConnectionState.HANDSHAKE, connection.getServerState());
            pair.first().packetListener().processClientPacket(packet, connection);
            assertEquals(ConnectionState.STATUS, connection.getClientState());
        }
    }

    @Test
    void knownPacksFutureIsPublishedBeforeAnImmediateReply() throws Exception {
        try (var pair = new ServerProcessPair()) {
            for (var process : List.of(pair.first(), pair.second())) {
                var connection = new ImmediateConnection(process);
                assertEquals(List.of(), connection.requestKnownPacks(List.of(SelectKnownPacksPacket.MINECRAFT_CORE)).get(1, TimeUnit.SECONDS));
                assertEquals(List.of(), connection.requestKnownPacks(List.of()).get(1, TimeUnit.SECONDS));
            }
        }
    }

    private static final class ImmediateConnection extends PlayerConnection {
        ImmediateConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            if (packet instanceof SelectKnownPacksPacket) receiveKnownPacksResponse(List.of());
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(25565);
        }
    }
}
