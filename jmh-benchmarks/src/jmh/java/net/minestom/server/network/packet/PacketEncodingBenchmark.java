package net.minestom.server.network.packet;

import net.minestom.server.ServerProcess;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;

/** One operation borrows, frames, and returns a buffer; all workers share one process's pools. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class PacketEncodingBenchmark {
    public enum Payload {
        SMALL_UNCOMPRESSED, LARGE_COMPRESSIBLE, LARGE_INCOMPRESSIBLE
    }

    @Param({"SMALL_UNCOMPRESSED", "LARGE_COMPRESSIBLE", "LARGE_INCOMPRESSIBLE"})
    public Payload payload;

    private ServerProcess process;
    private PacketBufferPool buffers;
    private PacketEncodingContext context;
    private ServerPacket packet;

    @Setup
    public void setup() throws DataFormatException {
        process = ServerProcess.create();
        buffers = process.packetBuffers();
        boolean compressed = payload != Payload.SMALL_UNCOMPRESSED;
        context = buffers.context(ConnectionState.PLAY, compressed ? 256 : 0);
        byte[] data = new byte[compressed ? 8192 : 64];
        if (payload == Payload.LARGE_COMPRESSIBLE) {
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 64);
        } else {
            new Random(0).nextBytes(data);
        }
        packet = new PluginMessagePacket("benchmark:data", data);
        // Validate the measured path before comparing revisions, including compressed output.
        NetworkBuffer buffer = buffers.get();
        try {
            context.write(buffer, packet);
            var result = PacketReading.readServer(buffer, ConnectionState.PLAY, compressed);
            if (!(result instanceof PacketReading.Result.Success<ServerPacket> success)
                    || success.packets().size() != 1
                    || !packet.equals(success.packets().getFirst().packet())
                    || buffer.readableBytes() != 0) {
                throw new IllegalStateException("Encoded packet did not round-trip");
            }
        } finally {
            buffers.add(buffer);
        }
    }

    @Benchmark
    public long encode() {
        NetworkBuffer buffer = buffers.get();
        try {
            context.write(buffer, packet);
            return buffer.writeIndex();
        } finally {
            buffers.add(buffer);
        }
    }

    @TearDown
    public void close() {
        process.close();
    }
}
