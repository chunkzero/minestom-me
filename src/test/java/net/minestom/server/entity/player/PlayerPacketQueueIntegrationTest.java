package net.minestom.server.entity.player;

import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.client.common.ClientPluginMessagePacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerPacketQueueIntegrationTest {

    @Test
    void throwingHandlerReportsAndContinues(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));

        List<Throwable> reported = new ArrayList<>();
        env.process().exception().setExceptionHandler(reported::add);
        AtomicInteger handled = new AtomicInteger();
        env.process().packetListener().setPlayListener(ClientPluginMessagePacket.class,
                (_, _) -> {
                    if (handled.getAndIncrement() == 0) throw new IllegalStateException("handler failure");
                });

        player.addPacketToQueue(new ClientPluginMessagePacket("minestom:test", new byte[0]));
        player.addPacketToQueue(new ClientPluginMessagePacket("minestom:test", new byte[0]));
        assertDoesNotThrow(player::interpretPacketQueue);

        // Play state is above the default suppression level, the failure is reported
        assertEquals(1, reported.size());
        assertInstanceOf(IllegalStateException.class, reported.getFirst());
        // The queue keeps draining and the player stays connected by default
        assertEquals(2, handled.get());
        assertTrue(player.getPlayerConnection().isOnline());
    }

    @Test
    void throwingHandlerUsesOwningProcess(Env defaultEnv) {
        try (var owner = ServerProcess.create()) {
            var connection = new PlayerConnection(owner) {
                @Override
                public void sendPacket(SendablePacket packet) {
                }

                @Override
                public SocketAddress getRemoteAddress() {
                    return new InetSocketAddress(0);
                }
            };
            var player = new Player(connection, new GameProfile(UUID.randomUUID(), "owner"));
            connection.setPlayer(player);
            connection.setClientState(ConnectionState.PLAY);
            List<Throwable> defaultErrors = new ArrayList<>();
            List<Throwable> ownerErrors = new ArrayList<>();
            defaultEnv.process().exception().setExceptionHandler(defaultErrors::add);
            owner.exception().setExceptionHandler(ownerErrors::add);
            AtomicInteger defaultHandled = new AtomicInteger();
            AtomicInteger ownerHandled = new AtomicInteger();
            AtomicInteger defaultEvents = new AtomicInteger();
            AtomicInteger ownerEvents = new AtomicInteger();
            defaultEnv.process().eventHandler().addListener(PlayerPacketEvent.class, _ -> defaultEvents.incrementAndGet());
            owner.eventHandler().addListener(PlayerPacketEvent.class, (process, event) -> {
                assertSame(owner, process);
                assertSame(player, event.getPlayer());
                ownerEvents.incrementAndGet();
            });
            defaultEnv.process().packetListener().setPlayListener(ClientPluginMessagePacket.class,
                    (_, _) -> defaultHandled.incrementAndGet());
            var failure = new IllegalStateException("owner handler failure");
            owner.packetListener().setPlayListener(ClientPluginMessagePacket.class, (_, _) -> {
                if (ownerHandled.getAndIncrement() == 0) throw failure;
            });

            player.addPacketToQueue(new ClientPluginMessagePacket("minestom:test", new byte[0]));
            player.addPacketToQueue(new ClientPluginMessagePacket("minestom:test", new byte[0]));
            assertDoesNotThrow(player::interpretPacketQueue);

            assertEquals(0, defaultHandled.get());
            assertEquals(0, defaultEvents.get());
            assertTrue(defaultErrors.isEmpty());
            assertEquals(List.of(failure), ownerErrors);
            assertEquals(2, ownerHandled.get());
            assertEquals(2, ownerEvents.get());
            assertTrue(player.getPlayerConnection().isOnline());
        }
    }
}
