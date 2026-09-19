package net.minestom.server.network;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.minestom.server.ServerProcess;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.listener.preplay.LoginListener;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.configuration.SelectKnownPacksPacket;
import net.minestom.server.network.packet.server.login.LoginDisconnectPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessConnectionOwnershipTest {
    @ParameterizedTest
    @EnumSource(value = ConnectionState.class, names = {"LOGIN", "PLAY"})
    void cancellationCallbacksCanCloseTheProcessWithoutLosingPlayerTeardown(ConnectionState state) {
        try (var pair = new ServerProcessPair()) {
            var process = pair.first();
            var connection = new ImmediateConnection(process);
            connection.setClientState(state);
            connection.setServerState(state);
            var player = process.connection().createPlayer(connection, new GameProfile(UUID.randomUUID(), "Closing"));
            var instance = process.instance().createInstanceContainer(ChunkLoader.noop());
            if (state == ConnectionState.PLAY) {
                player.setInstance(instance).join();
                process.ticker().tick(System.nanoTime());
            }
            var disconnected = new CopyOnWriteArrayList<PlayerDisconnectEvent>();
            process.eventHandler().addListener(PlayerDisconnectEvent.class, event -> disconnected.add(event));
            var task = player.scheduler().scheduleNextTick(() -> {});
            var callback = connection.fetchCookie("test:pending").whenComplete((_, _) -> process.close());
            connection.disconnect();
            assertTrue(callback.isCompletedExceptionally());
            assertFalse(connection.isOnline());
            assertFalse(task.isAlive());
            assertNull(process.connection().getPlayer(connection));
            assertEquals(1, disconnected.size());
            assertSame(player, disconnected.getFirst().getPlayer());
            assertFalse(instance.getEntities().contains(player));
            connection.disconnect();
            assertEquals(1, disconnected.size());
            pair.second().ticker().tick(System.nanoTime());
        }
    }

    @Test
    void disconnectCancelsPendingRepliesAndShutdownRejectsLateAdmission() {
        try (var pair = new ServerProcessPair()) {
            var first = new ImmediateConnection(pair.first());
            var second = new ImmediateConnection(pair.second());
            first.setClientState(ConnectionState.LOGIN);
            second.setClientState(ConnectionState.LOGIN);
            first.answerKnownPacks = false;
            var knownPacks = first.requestKnownPacks(List.of());
            var plugin = first.loginPluginMessageProcessor().request("test:pending", new byte[0]);
            var cookie = first.fetchCookie("test:pending");
            var otherCookie = second.fetchCookie("test:pending");
            var profile = new GameProfile(UUID.randomUUID(), "Pending");
            var player = pair.first().connection().createPlayer(first, profile);
            var task = player.scheduler().scheduleNextTick(() -> {});
            player.sendResourcePacks(ResourcePackInfo.resourcePackInfo(UUID.randomUUID(), URI.create("https://example.com/pack.zip"), "test"));
            var resourcePacks = player.getResourcePackFuture();
            pair.first().close();
            assertTrue(plugin.isCancelled());
            assertTrue(knownPacks.isCancelled());
            assertTrue(resourcePacks.isCancelled());
            assertTrue(cookie.isCancelled());
            assertFalse(task.isAlive());
            assertFalse(otherCookie.isDone());
            assertThrows(IllegalStateException.class, () -> first.fetchCookie("test:closed"));
            assertThrows(IllegalStateException.class, () -> first.requestKnownPacks(List.of()));
            assertThrows(IllegalStateException.class, () -> first.loginPluginMessageProcessor().request("test:closed", new byte[0]));
            assertThrows(IllegalStateException.class, () -> pair.first().connection().createPlayer(new ImmediateConnection(pair.first()), profile));
            pair.first().connection().doConfiguration(player, true);
            assertTrue(pair.first().connection().getConfigPlayers().isEmpty());
            second.receiveCookieResponse("test:pending", new byte[0]);
            assertTrue(otherCookie.isDone());
        }
    }

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

    @Test
    void failedLoginPluginReplyOnlyDisconnectsOnce() throws Exception {
        try (var pair = new ServerProcessPair()) {
            var connection = new ImmediateConnection(pair.second());
            connection.setClientState(ConnectionState.LOGIN);
            var loginThread = new CompletableFuture<Thread>();
            var errors = new CopyOnWriteArrayList<Throwable>();
            pair.second().exception().setExceptionHandler(errors::add);
            pair.second().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> {
                event.sendPluginRequest("test:failure", new byte[0]).completeExceptionally(new IllegalStateException("Invalid reply"));
                loginThread.complete(Thread.currentThread());
            });
            LoginListener.loginStartListener(new ClientLoginStartPacket("Failure", UUID.randomUUID()), connection);
            assertTrue(loginThread.get(5, TimeUnit.SECONDS).join(Duration.ofSeconds(5)));
            assertEquals(List.of(new LoginDisconnectPacket(LoginListener.INVALID_PROXY_RESPONSE)),
                    connection.packets.stream().filter(LoginDisconnectPacket.class::isInstance).toList());
            assertFalse(connection.isOnline());
            assertEquals(1, errors.size());
        }
    }

    private static final class ImmediateConnection extends PlayerConnection {
        private final List<SendablePacket> packets = new CopyOnWriteArrayList<>();
        private boolean answerKnownPacks = true;

        ImmediateConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            packets.add(packet);
            if (answerKnownPacks && packet instanceof SelectKnownPacksPacket) receiveKnownPacksResponse(List.of());
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(25565);
        }
    }
}
