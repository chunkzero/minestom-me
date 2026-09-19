package net.minestom.server.timer;

import net.minestom.server.ServerProcess;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.event.entity.EntityDespawnEvent;
import net.minestom.server.event.instance.InstanceUnregisterEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class SchedulerRemovalTest {
    @Test
    void objectRemovalClosesSchedulersAfterTeardownCallbacks() {
        try (var pair = new ServerProcessPair()) {
            var process = pair.first();
            var errors = new ArrayList<Throwable>();
            process.exceptionManager().setExceptionHandler(errors::add);
            var tasks = new ArrayList<Task>();
            var calls = new AtomicInteger();
            var entity = new Entity(process, EntityType.ZOMBIE) {
                @Override
                protected void despawn() {
                    tasks.add(scheduler().scheduleNextTick(calls::incrementAndGet));
                }
            };
            entity.eventNode().addListener(EntityDespawnEvent.class, _ -> {
                tasks.add(entity.scheduler().scheduleNextTick(calls::incrementAndGet));
            });
            entity.remove();
            var instance = process.instanceManager().createInstanceContainer(ChunkLoader.noop());
            instance.eventNode().addListener(InstanceUnregisterEvent.class, _ -> {
                tasks.add(instance.scheduler().scheduleNextTick(calls::incrementAndGet));
            });
            process.instanceManager().unregisterInstance(instance);
            var connection = new QuietConnection(process);
            var player = process.connectionManager().createPlayer(connection, new GameProfile(UUID.randomUUID(), "removal"));
            player.eventNode().addListener(PlayerDisconnectEvent.class, _ -> {
                tasks.add(player.scheduler().scheduleNextTick(calls::incrementAndGet));
            });
            connection.setServerState(ConnectionState.PLAY);
            connection.disconnect();
            process.ticker().tick(System.nanoTime());
            assertEquals(4, tasks.size());
            assertTrue(errors.isEmpty(), errors::toString);
            for (var task : tasks) {
                assertFalse(task.isAlive());
                assertTrue(task.owner().isClosed());
                task.owner().processTick();
            }
            assertEquals(0, calls.get());
        }
    }

    @Test
    void explicitPlayerRemovalClosesSchedulerAfterDisconnectCallbacks() {
        try (var pair = new ServerProcessPair()) {
            var process = pair.first();
            var errors = new ArrayList<Throwable>();
            process.exceptionManager().setExceptionHandler(errors::add);
            var connection = new QuietConnection(process);
            var player = process.connectionManager().createPlayer(connection, new GameProfile(UUID.randomUUID(), "explicit"));
            connection.setServerState(ConnectionState.PLAY);
            var tasks = new ArrayList<Task>();
            player.eventNode().addListener(PlayerDisconnectEvent.class, _ -> {
                tasks.add(player.scheduler().submitTask(TaskSchedule::park));
            });
            player.remove();
            assertFalse(connection.isOnline());
            assertTrue(player.isRemoved());
            assertTrue(player.scheduler().isClosed());
            assertFalse(tasks.isEmpty());
            assertTrue(tasks.stream().noneMatch(Task::isAlive));
            assertTrue(errors.isEmpty(), errors::toString);
        }
    }

    @Test
    void processShutdownRemovesPlayersUsingTheBaseDisconnectPath() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var errors = new CopyOnWriteArrayList<Throwable>();
            first.exceptionManager().setExceptionHandler(errors::add);
            var connection = new QuietConnection(first);
            var player = first.connectionManager().createPlayer(connection, new GameProfile(UUID.randomUUID(), "shutdown"));
            connection.setServerState(ConnectionState.PLAY);
            var disconnects = new AtomicInteger();
            first.eventHandler().addListener(PlayerDisconnectEvent.class, event -> {
                event.getPlayer().acquirable().assertOwnership();
                disconnects.incrementAndGet();
            });
            var instance = first.instanceManager().createInstanceContainer(ChunkLoader.noop());
            first.dispatcher().updateElement(player, instance.loadChunk(0, 0).join());
            first.ticker().tick(System.nanoTime());
            assertNotNull(player.acquirable().assignedThread());
            assertTrue(first.dispatcher().isAlive());
            assertDoesNotThrow(first::close);
            assertFalse(connection.isOnline());
            assertTrue(player.isRemoved());
            assertTrue(player.scheduler().isClosed());
            assertNull(first.connectionManager().getPlayer(connection));
            assertEquals(1, disconnects.get());
            assertFalse(first.server().isOpen());
            assertFalse(first.dispatcher().isAlive());
            assertTrue(errors.isEmpty(), errors::toString);
            pair.second().ticker().tick(System.nanoTime());
            assertTrue(pair.second().dispatcher().isAlive());
        }
    }

    @Test
    void queuedDisconnectRemovalSurvivesSchedulerClose() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var errors = new ArrayList<Throwable>();
            first.exceptionManager().setExceptionHandler(errors::add);
            for (boolean closeProcess : List.of(false, true)) {
                var connection = new QuietConnection(first);
                var player = first.connectionManager().createPlayer(connection, new GameProfile(UUID.randomUUID(), "pending"));
                connection.setServerState(ConnectionState.PLAY);
                connection.disconnect();
                assertFalse(player.isRemoved(), "Ordinary disconnect defers removal until a tick");
                player.scheduler().close();
                if (closeProcess) first.close();
                else first.ticker().tick(System.nanoTime());
                assertTrue(player.isRemoved());
                assertTrue(player.scheduler().isClosed());
                assertNull(first.connectionManager().getPlayer(connection));
            }
            assertTrue(errors.isEmpty(), errors::toString);
        }
    }

    private static final class QuietConnection extends PlayerConnection {
        QuietConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        }
    }
}
