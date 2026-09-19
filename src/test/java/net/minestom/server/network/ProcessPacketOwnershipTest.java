package net.minestom.server.network;

import net.kyori.adventure.text.Component;
import net.minestom.server.Auth;
import net.minestom.server.ServerProcess;
import net.minestom.server.Viewable;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.instrument.Instrument;
import net.minestom.server.network.packet.PacketEncodingContext;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.FramedPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.DisconnectPacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.login.LoginPluginRequestPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.network.packet.server.play.SetSlotPacket;
import net.minestom.server.network.packet.server.play.StartConfigurationPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.server.property.ServerProperties;
import net.minestom.server.utils.PacketSendingUtils;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static net.minestom.server.network.NetworkBuffer.VAR_INT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class ProcessPacketOwnershipTest {
    @ParameterizedTest
    @CsvSource({"0, 32", "32, 512"})
    void compressionStartsAfterItsQueuedNegotiationPacket(int firstThreshold, int secondThreshold) throws Exception {
        try (var pair = new ServerProcessPair();
             var first = new Session(pair.first()); var second = new Session(pair.second())) {
            var before = new LoginPluginRequestPacket(1, "test:before", new byte[200]);
            var after = new LoginPluginRequestPacket(2, "test:after", new byte[200]);
            var cached = new CachedPacket(after);
            for (var session : List.of(first, second)) {
                int threshold = session == first ? firstThreshold : secondThreshold;
                session.process.setCompressionThreshold(threshold);
                session.connection.setServerState(ConnectionState.LOGIN);
                session.connection.sendPacket(before);
                if (threshold > 0) session.connection.startCompression();
                else assertThrows(IllegalStateException.class, session.connection::startCompression);
                // Unstarted processes can still change settings; an existing negotiation must stay stable.
                session.process.setCompressionThreshold(1024);
                session.connection.sendPacket(cached);
                session.connection.flushSync();
                var buffer = session.takeBuffer();
                assertEquals(before, readOne(buffer, ConnectionState.LOGIN, false));
                if (threshold > 0) {
                    assertEquals(new SetCompressionPacket(threshold), readOne(buffer, ConnectionState.LOGIN, false));
                    var header = buffer.readOnly();
                    header.read(VAR_INT);
                    int dataLength = header.read(VAR_INT);
                    assertEquals(threshold < 200, dataLength > 0);
                }
                assertEquals(after, readOne(buffer, ConnectionState.LOGIN, threshold > 0));
                assertEquals(0, buffer.readableBytes());
                assertEquals(threshold, session.connection.packetContext().compressionThreshold());
            }
        }
    }

    @Test
    void groupedPacketsUseEachRecipientsTranslationPolicy() throws Exception {
        try (var enabled = ServerProcess.create(new Auth.Offline(),
                    ServerProperties.builder().automaticComponentTranslation(true).build());
             var disabled = ServerProcess.create(new Auth.Offline(),
                    ServerProperties.builder().automaticComponentTranslation(false).build());
             var first = new Session(enabled); var second = new Session(disabled)) {
            enabled.translation().setTranslator((_, _) -> Component.text("translated"));
            disabled.translation().setTranslator((_, _) -> Component.text("unexpected"));
            first.play(0);
            second.play(0);
            var recipients = List.of(first.player(), second.player());
            var packet = new SystemChatPacket(Component.translatable("test:key"), false);
            PacketSendingUtils.sendGroupedPacket(recipients, packet);
            assertEquals(List.of(new SystemChatPacket(Component.text("translated"), false)), first.flushPackets());
            assertEquals(List.of(packet), second.flushPackets());
        }
    }

    @Test
    void sharedCachedAndFramedPacketsUseTheRecipientsRegistryIds() throws Exception {
        try (var pair = new ServerProcessPair();
             var first = new Session(pair.first()); var second = new Session(pair.second())) {
            var packet = registryPacket(pair);
            var cached = new CachedPacket(packet);
            first.play(0);
            second.play(0);
            first.connection.sendPacket(cached);
            first.connection.flushSync();
            byte[] firstBytes = first.channel.takeBytes();
            second.connection.sendPacket(cached);
            second.connection.flushSync();
            byte[] secondBytes = second.channel.takeBytes();
            assertFalse(Arrays.equals(firstBytes, secondBytes), "The same key has different wire IDs");
            assertEquals(List.of(packet), decode(firstBytes, first.connection.packetContext()));
            assertEquals(List.of(packet), decode(secondBytes, second.connection.packetContext()));
            first.connection.sendPacket(cached);
            first.connection.flushSync();
            assertArrayEquals(firstBytes, first.channel.takeBytes());

            var context = first.connection.packetContext();
            var framed = new FramedPacket(context, packet, context.frame(packet));
            second.connection.sendPacket(framed);
            second.connection.flushSync();
            assertArrayEquals(secondBytes, second.channel.takeBytes());
        }
    }

    @Test
    void cachedAndFramedTransitionsAdvanceTheWriterState() throws Exception {
        try (var pair = new ServerProcessPair();
             var first = new Session(pair.first()); var second = new Session(pair.second())) {
            var packet = registryPacket(pair);
            first.play(0);
            second.play(32);
            first.connection.setServerState(ConnectionState.CONFIGURATION);
            second.connection.setServerState(ConnectionState.CONFIGURATION);
            var finish = new FinishConfigurationPacket();
            var context = first.connection.packetContext();
            first.connection.sendPacket(new CachedPacket(finish));
            second.connection.sendPacket(new FramedPacket(context, finish, context.frame(finish)));
            for (var session : List.of(first, second)) {
                session.connection.sendPacket(packet);
                session.connection.flushSync();
                boolean compressed = session == second;
                var buffer = session.takeBuffer();
                assertEquals(finish, readOne(buffer, ConnectionState.CONFIGURATION, compressed));
                assertEquals(packet, readOne(buffer, ConnectionState.PLAY, compressed));
                assertEquals(ConnectionState.PLAY, session.connection.getServerState());
                assertEquals(0, buffer.readableBytes());
            }
        }
    }

    @Test
    void cachedRepresentationsDistinguishStateAndCompression() throws Exception {
        try (var pair = new ServerProcessPair()) {
            var packet = new PluginMessagePacket("test:context", new byte[300]);
            var cached = new CachedPacket(packet);
            var play = pair.first().packetBuffers().context(ConnectionState.PLAY, 0);
            var configuration = pair.first().packetBuffers().context(ConnectionState.CONFIGURATION, 0);
            byte[] playBytes = bytes(cached.body(play));
            byte[] configurationBytes = bytes(cached.body(configuration));
            assertFalse(Arrays.equals(playBytes, configurationBytes));
            for (var process : List.of(pair.first(), pair.second())) {
                for (var state : List.of(ConnectionState.PLAY, ConnectionState.CONFIGURATION)) {
                    for (int threshold : List.of(0, 32, 512)) {
                        var context = process.packetBuffers().context(state, threshold);
                        assertEquals(List.of(packet), decode(bytes(cached.body(context)), context));
                    }
                }
            }
        }
    }

    @Test
    void bufferReuseCopiesAndCompressedReadsPreserveRegistryContext() throws Exception {
        try (var pair = new ServerProcessPair()) {
            var packet = registryPacket(pair);
            var firstPool = pair.first().packetBuffers();
            var secondPool = pair.second().packetBuffers();
            var buffer = firstPool.get();
            buffer.resize(buffer.capacity() + 1);
            firstPool.add(buffer);
            assertSame(buffer, firstPool.get());
            // Resizing a borrowed buffer and allocating a trimmed frame both keep the owner.
            firstPool.add(buffer);
            var framed = PacketWriting.allocateTrimmedPacket(NetworkBuffer.staticBuffer(1, pair.first().registries()),
                    PacketVanilla.SERVER_PACKET_PARSER, ConnectionState.PLAY, packet, 1);
            assertSame(pair.first().registries(), framed.registries());
            for (var copy : List.of(framed.copy(0, framed.writeIndex()), framed.readOnly(),
                    framed.slice(0, framed.writeIndex(), 0, framed.writeIndex()))) {
                assertSame(pair.first().registries(), copy.registries());
                assertEquals(packet, readOne(copy, ConnectionState.PLAY, true));
            }
            for (var process : List.of(pair.first(), pair.second(), pair.first())) {
                var item = packet.itemStack();
                var type = ItemStack.NETWORK_TYPE.maxLength(256).lengthPrefixed(256);
                var itemBuffer = NetworkBuffer.resizableBuffer(1, process.registries());
                itemBuffer.write(type, item);
                assertEquals(item, itemBuffer.read(type));
                assertEquals(itemBuffer.writeIndex(), type.sizeOf(item, process.registries()));
                var encoded = process.packetBuffers().context(ConnectionState.PLAY, 1).frame(packet);
                var result = PacketReading.readPackets(encoded, PacketVanilla.SERVER_PACKET_PARSER,
                        ConnectionState.PLAY, PacketVanilla::nextServerState, true,
                        (info, input) -> {
                            assertSame(process.registries(), input.registries());
                            return info.serializer().read(input);
                        }, process.packetBuffers());
                var success = assertInstanceOf(PacketReading.Result.Success.class, result);
                assertEquals(packet, ((PacketReading.ParsedPacket<?>) success.packets().getFirst()).packet());
                var reused = process.packetBuffers().get();
                assertSame(process.registries(), reused.registries());
                assertEquals(0, reused.readIndex());
                assertEquals(0, reused.writeIndex());
                process.packetBuffers().add(reused);
            }
            // Scratch storage may be borrowed for a standalone read with another registry collection.
            // Only the temporary slice should acquire that collection.
            var foreignFrame = secondPool.context(ConnectionState.PLAY, 1).frame(packet);
            PacketReading.readPackets(foreignFrame, PacketVanilla.SERVER_PACKET_PARSER,
                    ConnectionState.PLAY, PacketVanilla::nextServerState, true,
                    (info, input) -> {
                        assertSame(pair.second().registries(), input.registries());
                        return info.serializer().read(input);
                    }, firstPool);
            var scratch = firstPool.get();
            assertSame(pair.first().registries(), scratch.registries());
            firstPool.add(scratch);
            var foreign = secondPool.get();
            assertThrows(IllegalArgumentException.class, () -> firstPool.add(foreign));
            secondPool.add(foreign);
        }
    }

    @Test
    void shortWritesAndFailureReturnBuffersToTheirOwner() throws Exception {
        try (var pair = new ServerProcessPair();
             var first = new Session(pair.first()); var second = new Session(pair.second())) {
            var packet = registryPacket(pair);
            first.play(0);
            second.play(32);
            byte[] expectedFirst = expectedBytes(first.connection.packetContext(), packet);
            byte[] expectedSecond = expectedBytes(second.connection.packetContext(), packet);
            first.channel.writeLimit = 3;
            second.channel.writeLimit = 5;
            first.connection.sendPacket(new CachedPacket(packet));
            second.connection.sendPacket(packet);
            while (first.channel.output.size() < expectedFirst.length || second.channel.output.size() < expectedSecond.length) {
                if (first.channel.output.size() < expectedFirst.length) first.connection.flushSync();
                if (second.channel.output.size() < expectedSecond.length) second.connection.flushSync();
            }
            assertArrayEquals(expectedFirst, first.channel.takeBytes());
            assertArrayEquals(expectedSecond, second.channel.takeBytes());

            var borrowed = pair.first().packetBuffers().get();
            pair.first().packetBuffers().add(borrowed);
            first.channel.failWrite = true;
            first.connection.sendPacket(packet);
            assertThrows(IOException.class, first.connection::flushSync);
            var returned = pair.first().packetBuffers().get();
            assertSame(borrowed, returned, "A failed channel write must return its buffer");
            pair.first().packetBuffers().add(returned);

            second.connection.sendPacket(packet);
            second.connection.flushSync(); // leave a partial frame outstanding
            pair.second().close();
            second.connection.cleanup(); // late returns must not reopen a closed pool
            var unpooled = pair.second().packetBuffers().get();
            pair.second().packetBuffers().add(unpooled);
            assertNotSame(unpooled, pair.second().packetBuffers().get());
            first.channel.failWrite = false;
            first.channel.writeLimit = Integer.MAX_VALUE;
            first.connection.sendPacket(packet);
            first.connection.flushSync();
            assertEquals(List.of(packet), decode(first.channel.takeBytes(), first.connection.packetContext()));
        }
    }

    @Test
    void bufferedPacketsRejectForeignRepresentations() throws Exception {
        try (var pair = new ServerProcessPair(); var second = new Session(pair.second())) {
            second.play(0);
            var packet = new PluginMessagePacket("test:buffer", new byte[20]);
            for (var context : List.of(
                    pair.first().packetBuffers().context(ConnectionState.PLAY, 0),
                    pair.second().packetBuffers().context(ConnectionState.PLAY, 32))) {
                var body = context.frame(packet);
                second.connection.sendPacket(new BufferedPacket(context, body, 0, body.writeIndex()));
                assertThrows(IllegalArgumentException.class, second.connection::flushSync);
                assertEquals(0, second.channel.output.size());
                second.connection.cleanup();
            }
        }
    }

    @Test
    void ticksExclusionsAndCloseAreScopedToTheBatchOwner() throws Exception {
        try (var pair = new ServerProcessPair();
             var first = new Session(pair.first()); var sibling = new Session(pair.first());
             var plainSibling = new Session(pair.first()); var second = new Session(pair.second())) {
            first.play(0);
            sibling.play(32);
            plainSibling.play(0);
            second.play(512);
            var firstPlayer = first.player();
            var siblingPlayer = sibling.player();
            var plainPlayer = plainSibling.player();
            var secondPlayer = second.player();
            assertEquals(firstPlayer.getEntityId(), secondPlayer.getEntityId());
            var firstView = new TestViewable(Set.of(firstPlayer, siblingPlayer, plainPlayer));
            var secondView = new TestViewable(Set.of(secondPlayer));
            var packet = registryPacket(pair);
            var middle = new PluginMessagePacket("test:middle", new byte[300]);
            var last = new PluginMessagePacket("test:last", new byte[300]);
            var batcher = pair.first().packetBatcher();
            batcher.prepareViewablePacket(firstView, packet, firstPlayer);
            batcher.prepareViewablePacket(firstView, middle);
            batcher.prepareViewablePacket(firstView, packet, firstPlayer);
            batcher.prepareViewablePacket(firstView, last, siblingPlayer);
            pair.second().packetBatcher().prepareViewablePacket(secondView, packet);
            pair.first().ticker().tick(System.nanoTime());
            assertEquals(List.of(middle, last), first.flushPackets());
            assertEquals(List.of(packet, middle, packet), sibling.flushPackets());
            assertEquals(List.of(packet, middle, packet, last), plainSibling.flushPackets());
            assertTrue(second.flushPackets().isEmpty(), "Ticking A must leave B's batch pending");
            pair.second().ticker().tick(System.nanoTime());
            assertEquals(List.of(packet), second.flushPackets());

            batcher.prepareViewablePacket(firstView, packet);
            var outstanding = pair.first().packetBuffers().get();
            pair.first().close();
            pair.first().packetBuffers().add(outstanding);
            assertNotSame(outstanding, pair.first().packetBuffers().get());
            assertThrows(RejectedExecutionException.class, () -> batcher.prepareViewablePacket(firstView, packet));
            batcher.flush();
            assertEquals(0, first.channel.output.size());
            pair.second().packetBatcher().prepareViewablePacket(secondView, packet);
            pair.second().ticker().tick(System.nanoTime());
            assertEquals(List.of(packet), second.flushPackets());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {493, 32749})
    void incompressiblePacketsSurviveBufferGrowth(int length) throws Exception {
        try (var pair = new ServerProcessPair(); var session = new Session(pair.first())) {
            session.play(256);
            byte[] data = new byte[length];
            new Random(0).nextBytes(data);
            var packet = new PluginMessagePacket("test:data", data);
            pair.first().packetBatcher().prepareViewablePacket(new TestViewable(Set.of(session.player())), packet);
            pair.first().packetBatcher().flush();
            assertEquals(List.of(packet), session.flushPackets());
            session.connection.sendPacket(new CachedPacket(packet));
            assertEquals(List.of(packet), session.flushPackets());
            session.connection.sendPacket(packet);
            assertEquals(List.of(packet), session.flushPackets());
        }
    }

    @Test
    void queuedConfigurationTransitionDiscardsTheStalePlayBatch() throws Exception {
        try (var pair = new ServerProcessPair(); var session = new Session(pair.first())) {
            session.play(32);
            var viewable = new TestViewable(Set.of(session.player()));
            var transition = new StartConfigurationPacket();
            var stale = new PluginMessagePacket("test:stale", new byte[100]);
            var next = new PluginMessagePacket("test:configuration", new byte[100]);
            session.connection.sendPacket(transition);
            pair.first().packetBatcher().prepareViewablePacket(viewable, stale);
            pair.first().packetBatcher().flush();
            session.connection.sendPacket(next);
            session.connection.flushSync();
            var buffer = session.takeBuffer();
            assertEquals(transition, readOne(buffer, ConnectionState.PLAY, true));
            assertEquals(next, readOne(buffer, ConnectionState.CONFIGURATION, true));
            assertEquals(0, buffer.readableBytes());
            assertTrue(session.connection.isOnline());
        }
    }

    @Test
    void shutdownAllowsTheFinalCompressedDisconnectToDrain() throws Exception {
        try (var pair = new ServerProcessPair();
             var first = new Session(pair.first()); var second = new Session(pair.second())) {
            first.play(1);
            second.play(32);
            var reason = Component.text("Server shutting down");
            first.connection.kick(reason);
            pair.first().close();
            assertEquals(List.of(new DisconnectPacket(reason)), first.flushPackets());
            assertFalse(first.connection.isOnline());
            var packet = new PluginMessagePacket("test:alive", new byte[100]);
            second.connection.sendPacket(packet);
            assertEquals(List.of(packet), second.flushPackets());
        }
    }

    @Test
    void mixedViewersDoNotPreventDeliveryToOwnedViewersOrOtherBatches() throws Exception {
        try (var pair = new ServerProcessPair();
             var first = new Session(pair.first()); var sibling = new Session(pair.first());
             var second = new Session(pair.second())) {
            first.play(0);
            sibling.play(0);
            second.play(0);
            var packet = new PluginMessagePacket("test:owned", new byte[10]);
            var batcher = pair.first().packetBatcher();
            batcher.prepareViewablePacket(new TestViewable(Set.of(first.player(), second.player())), packet);
            batcher.prepareViewablePacket(new TestViewable(Set.of(sibling.player())), packet);
            batcher.flush();
            assertEquals(List.of(packet), first.flushPackets());
            assertEquals(List.of(packet), sibling.flushPackets());
            assertTrue(second.flushPackets().isEmpty());
        }
    }

    @Test
    void cancelledOutgoingPacketIsConsumedBeforeTheNextPacket() throws Exception {
        try (var pair = new ServerProcessPair(); var session = new Session(pair.first())) {
            session.play(32);
            session.player();
            var cancelled = new PluginMessagePacket("test:cancelled", new byte[100]);
            var next = new PluginMessagePacket("test:next", new byte[100]);
            pair.first().eventHandler().addListener(PlayerPacketOutEvent.class, event -> {
                if (event.getPacket() == cancelled) event.setCancelled(true);
            });
            session.connection.sendPacket(cancelled);
            session.connection.sendPacket(next);
            assertEquals(List.of(next), session.flushPackets());
        }
    }

    private static SetSlotPacket registryPacket(ServerProcessPair pair) {
        var value = pair.first().registries().instrument().get(Instrument.PONDER_GOAT_HORN);
        var first = pair.first().registries().instrument().register("test:shared", value);
        pair.second().registries().instrument().register("test:padding", value);
        var second = pair.second().registries().instrument().register("test:shared", value);
        assertNotEquals(pair.first().registries().instrument().getId(first), pair.second().registries().instrument().getId(second));
        return new SetSlotPacket(0, 0, (short) 0, ItemStack.of(Material.GOAT_HORN).with(DataComponents.INSTRUMENT, first));
    }

    private static byte[] expectedBytes(PacketEncodingContext context, ServerPacket packet) {
        var buffer = NetworkBuffer.resizableBuffer(context.registries());
        PacketWriting.writeFramedPacket(buffer, context.state(), packet, context.compressionThreshold());
        return bytes(buffer);
    }

    private static byte[] bytes(NetworkBuffer buffer) {
        byte[] bytes = new byte[(int) buffer.writeIndex()];
        buffer.copyTo(0, bytes, 0, bytes.length);
        return bytes;
    }

    private static ServerPacket readOne(NetworkBuffer buffer, ConnectionState state, boolean compressed) throws Exception {
        var result = PacketReading.readServer(buffer, state, compressed);
        var success = assertInstanceOf(PacketReading.Result.Success.class, result);
        return (ServerPacket) ((PacketReading.ParsedPacket<?>) success.packets().getFirst()).packet();
    }

    private static List<ServerPacket> decode(byte[] bytes, PacketEncodingContext context) throws Exception {
        var buffer = NetworkBuffer.wrap(bytes, 0, bytes.length, context.registries());
        var packets = new ArrayList<ServerPacket>();
        while (buffer.readableBytes() > 0) packets.add(readOne(buffer, context.state(), context.compressionThreshold() > 0));
        return packets;
    }

    private static final class Session implements AutoCloseable {
        final ServerProcess process;
        final RecordingChannel channel = new RecordingChannel();
        final PlayerSocketConnection connection;

        Session(ServerProcess process) {
            this.process = process;
            connection = new PlayerSocketConnection(process, channel, new InetSocketAddress(0),
                    Thread.currentThread(), Thread.currentThread());
        }

        void play(int threshold) throws Exception {
            process.setCompressionThreshold(threshold);
            connection.setServerState(ConnectionState.LOGIN);
            if (threshold > 0) {
                connection.startCompression();
                connection.flushSync();
                assertEquals(new SetCompressionPacket(threshold), readOne(takeBuffer(), ConnectionState.LOGIN, false));
            }
            connection.setServerState(ConnectionState.PLAY);
        }

        Player player() {
            return new Player(connection, new GameProfile(UUID.randomUUID(), "packet-test"));
        }

        NetworkBuffer takeBuffer() {
            var bytes = channel.takeBytes();
            return NetworkBuffer.wrap(bytes, 0, bytes.length, process.registries());
        }

        List<ServerPacket> flushPackets() throws Exception {
            connection.flushSync();
            return decode(channel.takeBytes(), connection.packetContext());
        }

        @Override
        public void close() throws IOException {
            connection.cleanup();
            channel.close();
        }
    }

    private record TestViewable(Set<Player> getViewers) implements Viewable {
        @Override public boolean addViewer(Player player) { throw new UnsupportedOperationException(); }
        @Override public boolean removeViewer(Player player) { throw new UnsupportedOperationException(); }
    }

    /** Captures the real socket encoder's output, with deterministic partial writes and failures. */
    private static final class RecordingChannel extends SocketChannel {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int writeLimit = Integer.MAX_VALUE;
        boolean failWrite;

        RecordingChannel() { super(SelectorProvider.provider()); }

        byte[] takeBytes() {
            byte[] bytes = output.toByteArray();
            output.reset();
            return bytes;
        }

        @Override public int write(ByteBuffer source) throws IOException {
            if (failWrite) throw new IOException("test write failure");
            int length = Math.min(writeLimit, source.remaining());
            byte[] bytes = new byte[length];
            source.get(bytes);
            output.writeBytes(bytes);
            return length;
        }
        @Override public boolean isConnected() { return isOpen(); }
        @Override protected void implCloseSelectableChannel() { }
        @Override protected void implConfigureBlocking(boolean block) { }
        @Override public SocketChannel bind(SocketAddress local) { throw new UnsupportedOperationException(); }
        @Override public <T> SocketChannel setOption(SocketOption<T> name, T value) { throw new UnsupportedOperationException(); }
        @Override public <T> T getOption(SocketOption<T> name) { throw new UnsupportedOperationException(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public SocketChannel shutdownInput() { throw new UnsupportedOperationException(); }
        @Override public SocketChannel shutdownOutput() { throw new UnsupportedOperationException(); }
        @Override public Socket socket() { throw new UnsupportedOperationException(); }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress remote) { throw new UnsupportedOperationException(); }
        @Override public boolean finishConnect() { throw new UnsupportedOperationException(); }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public SocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public int read(ByteBuffer destination) { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] destinations, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] sources, int offset, int length) { throw new UnsupportedOperationException(); }
    }
}
