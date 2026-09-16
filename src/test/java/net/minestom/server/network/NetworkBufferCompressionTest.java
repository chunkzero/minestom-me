package net.minestom.server.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkBufferCompressionTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 512})
    void growsUntilTheCompressedStreamIsComplete(int capacity) throws Exception {
        byte[] data = new byte[512];
        new Random(0).nextBytes(data);
        var input = NetworkBuffer.wrap(data, 0, data.length);
        var output = NetworkBuffer.resizableBuffer(capacity);
        output.write(NetworkBuffer.BYTE, (byte) 42);
        long length = input.compress(0, data.length, output);
        assertEquals(length + 1, output.writeIndex());
        assertEquals((byte) 42, output.read(NetworkBuffer.BYTE));
        var restored = NetworkBuffer.staticBuffer(data.length);
        assertEquals(data.length, output.decompress(1, length, restored));
        assertArrayEquals(data, restored.read(NetworkBuffer.RAW_BYTES));
    }

    @Test
    void fixedBufferRejectsIncompleteCompressionAndCanRetry() throws Exception {
        byte[] data = new byte[512];
        new Random(0).nextBytes(data);
        var input = NetworkBuffer.wrap(data, 0, data.length);
        var output = NetworkBuffer.staticBuffer(513);
        output.write(NetworkBuffer.BYTE, (byte) 42);
        assertThrows(IndexOutOfBoundsException.class, () -> input.compress(0, data.length, output));
        assertEquals(1, output.writeIndex());
        output.resize(1024);
        long length = input.compress(0, data.length, output);
        assertEquals((byte) 42, output.read(NetworkBuffer.BYTE));
        var restored = NetworkBuffer.staticBuffer(data.length);
        assertEquals(data.length, output.decompress(1, length, restored));
        assertArrayEquals(data, restored.read(NetworkBuffer.RAW_BYTES));
    }
}
