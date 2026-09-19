package net.minestom.server.network;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.Auth;
import net.minestom.server.ServerProcess;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.configuration.RegistryDataPacket;
import net.minestom.server.property.ServerProperties;
import net.minestom.server.utils.collection.ConcurrentMessageQueues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Properties;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPropertiesTest {
    private static final CompoundBinaryTag NESTED = CompoundBinaryTag.builder()
            .put("child", CompoundBinaryTag.builder().putString("value", "test").build()).build();

    @Test
    void nbtLimitsApplyToWritesSizingAndDerivedBuffers() {
        var strict = ServerProperties.builder().nbtMaxDepth(1).build();
        var permissive = ServerProperties.builder().nbtMaxDepth(2).build();
        var buffer = NetworkBuffer.resizableBuffer(1, null, permissive);
        buffer.write(NetworkBuffer.NBT_COMPOUND, NESTED);
        assertEquals(buffer.writeIndex(), NetworkBuffer.NBT_COMPOUND.sizeOf(NESTED, null, permissive));
        assertThrows(IllegalArgumentException.class, () -> NetworkBuffer.NBT_COMPOUND.sizeOf(NESTED, null, strict));
        assertThrows(IllegalArgumentException.class, () ->
                NetworkBuffer.resizableBuffer(1, null, strict).write(NetworkBuffer.NBT_COMPOUND, NESTED));
        for (var type : List.of(NetworkBuffer.NBT_COMPOUND.maxLength(256), NetworkBuffer.NBT_COMPOUND.lengthPrefixed(256))) {
            assertThrows(IllegalArgumentException.class, () ->
                    NetworkBuffer.resizableBuffer(1, null, strict).write(type, NESTED));
        }
        for (var derived : List.of(buffer.copy(0, buffer.writeIndex()), buffer.readOnly(),
                buffer.slice(0, buffer.writeIndex(), 0, buffer.writeIndex()))) {
            assertSame(permissive, derived.properties());
            assertEquals(NESTED, derived.read(NetworkBuffer.NBT_COMPOUND));
        }
        var bytes = buffer.read(NetworkBuffer.RAW_BYTES);
        var strictInput = NetworkBuffer.wrap(bytes, 0, bytes.length, null, strict);
        assertThrows(IllegalArgumentException.class, () -> strictInput.read(NetworkBuffer.NBT_COMPOUND));
    }

    @Test
    void untrustedNbtUsesTheReadersByteBudget() {
        var properties = ServerProperties.builder().nbtMaxBytes(16).build();
        byte[] bytes = NetworkBuffer.makeArray(NetworkBuffer.NBT_COMPOUND, NESTED, null, properties);
        var input = NetworkBuffer.wrap(bytes, 0, bytes.length, null, properties);
        assertThrows(IllegalArgumentException.class, () -> input.read(NetworkBuffer.UNTRUSTED_NBT_COMPOUND));
        input.readIndex(0);
        assertEquals(NESTED, input.read(NetworkBuffer.NBT_COMPOUND));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void framingAndDecompressionKeepTheRecipientsLimits(int compression) throws Exception {
        var packet = new RegistryDataPacket("test:registry", List.of(new RegistryDataPacket.Entry("test:entry", NESTED)));
        var properties = ServerProperties.builder().nbtMaxDepth(2).pooledBufferSize(1).build();
        try (var process = ServerProcess.create(new Auth.Offline(), properties)) {
            var context = process.packetBuffers().context(ConnectionState.CONFIGURATION, compression);
            var frame = context.frame(packet);
            assertSame(process.properties(), frame.properties());
            var result = PacketReading.readPackets(frame, PacketVanilla.SERVER_PACKET_PARSER,
                    ConnectionState.CONFIGURATION, PacketVanilla::nextServerState, compression > 0,
                    (info, input) -> {
                        assertSame(process.properties(), input.properties());
                        return info.serializer().read(input);
                    }, process.packetBuffers());
            assertTrue(result instanceof PacketReading.Result.Success<?>);
            var borrowed = process.packetBuffers().get();
            assertSame(process.properties(), borrowed.properties());
            process.packetBuffers().add(borrowed);

            byte[] bytes = context.frame(packet).read(NetworkBuffer.RAW_BYTES);
            var strict = ServerProperties.builder().nbtMaxDepth(1).build();
            var strictInput = NetworkBuffer.wrap(bytes, 0, bytes.length, process.registries(), strict);
            assertThrows(RuntimeException.class, () ->
                    PacketReading.readServer(strictInput, ConnectionState.CONFIGURATION, compression > 0));
            var foreign = NetworkBuffer.staticBuffer(16, process.registries(), strict);
            assertThrows(IllegalArgumentException.class, () -> process.packetBuffers().add(foreign));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void packetLimitsApplyEvenWhenTheBufferAlreadyHasCapacity(int compression) {
        var packet = new PluginMessagePacket("test:large", new byte[256]);
        var strict = ServerProperties.builder().maxPacketSize(64).build();
        var output = NetworkBuffer.staticBuffer(4096, null, strict);
        assertThrows(IllegalStateException.class, () ->
                PacketWriting.writeFramedPacket(output, ConnectionState.PLAY, packet, compression));

        var permissive = NetworkBuffer.resizableBuffer();
        PacketWriting.writeFramedPacket(permissive, ConnectionState.PLAY, packet, compression);
        var bytes = permissive.read(NetworkBuffer.RAW_BYTES);
        var input = NetworkBuffer.wrap(bytes, 0, bytes.length, null, strict);
        assertThrows(DataFormatException.class, () -> PacketReading.readServer(input, ConnectionState.PLAY, compression > 0));
    }

    @Test
    void queuedPacketAtTheLimitHasRoomForFraming() {
        var packet = new PluginMessagePacket("test:limit", new byte[56]);
        var expected = NetworkBuffer.resizableBuffer();
        PacketWriting.writeFramedPacket(expected, ConnectionState.PLAY, packet, 0);
        var properties = ServerProperties.builder().maxPacketSize((int) expected.writeIndex() - 3).build();
        var output = NetworkBuffer.staticBuffer(1, null, properties);
        var queue = ConcurrentMessageQueues.<PluginMessagePacket>mpscArrayQueue(2);
        queue.offer(packet);
        PacketWriting.writeQueue(output, queue, 1, (buffer, value) -> {
            PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, value, 0);
            return true;
        });
        assertTrue(queue.isEmpty());
        assertArrayEquals(expected.read(NetworkBuffer.RAW_BYTES), output.read(NetworkBuffer.RAW_BYTES));
    }

    @Test
    void sharedCacheCannotBypassAnotherProcessesLimits() {
        var packet = new RegistryDataPacket("test:registry", List.of(new RegistryDataPacket.Entry("test:entry", NESTED)));
        var cached = new CachedPacket(packet);
        var source = new Properties();
        source.setProperty("minestom.cached-packet.mutable", "true");
        try (var first = ServerProcess.create(new Auth.Offline(), ServerProperties.builder(source).nbtMaxDepth(2).build());
             var second = ServerProcess.create(new Auth.Offline(), ServerProperties.builder(source).nbtMaxDepth(1).build())) {
            var firstContext = first.packetBuffers().context(ConnectionState.CONFIGURATION, 0);
            var secondContext = second.packetBuffers().context(ConnectionState.CONFIGURATION, 0);
            var body = cached.body(firstContext);
            assertSame(body, cached.body(firstContext));
            assertThrows(IllegalArgumentException.class, () -> cached.body(secondContext));
            first.properties().cachedPacket().set(false);
            assertNull(cached.body(firstContext));
            assertFalse(cached.isValid());
            assertTrue(second.properties().cachedPacket().get());
        }
    }
}
