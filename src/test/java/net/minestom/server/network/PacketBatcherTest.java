package net.minestom.server.network;

import net.minestom.server.ServerProcess;
import net.minestom.server.Viewable;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class PacketBatcherTest {
    @Test
    void preparationDuringFlushPreservesTheNextBatchAndNonSocketExclusions() throws Exception {
        try (var pair = new ServerProcessPair(); var executor = Executors.newSingleThreadExecutor()) {
            var first = new RecordingConnection(pair.first());
            var excluded = new RecordingConnection(pair.first());
            var foreign = new RecordingConnection(pair.second());
            var firstPlayer = first.player();
            var excludedPlayer = excluded.player();
            var foreignPlayer = foreign.player();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var blocked = new AtomicBoolean();
            var viewable = new Viewable() {
                @Override
                public Set<Player> getViewers() {
                    if (blocked.compareAndSet(false, true)) {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    }
                    return Set.of(firstPlayer, excludedPlayer, foreignPlayer);
                }
                @Override public boolean addViewer(Player player) { throw new UnsupportedOperationException(); }
                @Override public boolean removeViewer(Player player) { throw new UnsupportedOperationException(); }
            };
            var batcher = pair.first().packetBatcher();
            var before = new KeepAlivePacket(1);
            var during = new KeepAlivePacket(2);
            batcher.prepareViewablePacket(viewable, before, excludedPlayer);
            var flush = executor.submit(batcher::flush);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                batcher.prepareViewablePacket(viewable, during);
            } finally {
                release.countDown();
            }
            flush.get(5, TimeUnit.SECONDS);
            batcher.flush();
            assertEquals(List.of(before, during), first.packets);
            assertEquals(List.of(during), excluded.packets);
            assertTrue(foreign.packets.isEmpty());
        }
    }

    private static final class RecordingConnection extends PlayerConnection {
        final List<SendablePacket> packets = new CopyOnWriteArrayList<>();

        RecordingConnection(ServerProcess process) {
            super(process);
            setServerState(ConnectionState.PLAY);
        }

        Player player() {
            return new Player(this, new GameProfile(UUID.randomUUID(), "batch-test"));
        }

        @Override public void sendPacket(SendablePacket packet) { packets.add(packet); }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
    }
}
