package net.minestom.server.network.packet;

import net.minestom.server.ServerProcess;
import net.minestom.server.Viewable;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.player.PlayerSocketConnection;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded producer bursts; vary JMH's thread count to measure contention within one process. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
@Fork(2)
@State(Scope.Benchmark)
public class PacketOwnershipBenchmark {
    private static final int OPERATIONS = 65_536;
    private static final KeepAlivePacket PACKET = new KeepAlivePacket(0);
    private ServerProcess process;
    private SocketChannel channel;
    private PlayerSocketConnection connection;

    @Setup
    public void setup() throws IOException {
        process = ServerProcess.create();
        channel = SocketChannel.open();
        connection = new PlayerSocketConnection(process, channel, new InetSocketAddress(0),
                Thread.currentThread(), Thread.currentThread());
    }

    @TearDown(Level.Iteration)
    public void drain() {
        connection.cleanup();
        process.packetBatcher().flush();
    }

    @TearDown
    public void close() throws IOException {
        channel.close();
        process.close();
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS)
    public void borrowAndReturn() {
        var pool = process.packetBuffers();
        for (int i = 0; i < OPERATIONS; i++) pool.add(pool.get());
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS)
    public void sendPacket() {
        for (int i = 0; i < OPERATIONS; i++) connection.sendPacket(PACKET);
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS)
    public void prepareViewable(ThreadView state) {
        var batcher = process.packetBatcher();
        for (int i = 0; i < OPERATIONS; i++) batcher.prepareViewablePacket(state.viewable, PACKET);
    }

    @State(Scope.Thread)
    public static class ThreadView {
        private final Viewable viewable = new Viewable() {
            @Override public Set<Player> getViewers() { return Set.of(); }
            @Override public boolean addViewer(Player player) { throw new UnsupportedOperationException(); }
            @Override public boolean removeViewer(Player player) { throw new UnsupportedOperationException(); }
        };
    }
}
