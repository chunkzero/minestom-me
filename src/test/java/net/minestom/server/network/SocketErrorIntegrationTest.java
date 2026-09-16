package net.minestom.server.network;

import net.minestom.server.ServerFlag;
import net.minestom.server.ServerProcess;
import net.minestom.server.network.packet.client.status.StatusRequestPacket;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
@Timeout(10)
class SocketErrorIntegrationTest {
    @ParameterizedTest
    @CsvSource({"HANDSHAKE, true, 0, false", "STATUS, true, 1, false", "STATUS, false, 1, true"})
    void errorsUseOwningProcessAndRespectDefaults(ConnectionState state, boolean malformed,
                                                int reportedCount, boolean staysOnline, Env env) throws IOException {
        try (var owner = ServerProcess.create();
             var server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (var client = SocketChannel.open(server.getLocalAddress());
                 var accepted = server.accept()) {
                var connection = new PlayerSocketConnection(owner, accepted, accepted.getRemoteAddress(),
                        Thread.currentThread(), Thread.currentThread());
                connection.setClientState(state);
                List<Throwable> defaultErrors = new ArrayList<>();
                List<Throwable> ownerErrors = new ArrayList<>();
                env.process().exception().setExceptionHandler(defaultErrors::add);
                owner.exception().setExceptionHandler(ownerErrors::add);
                var failure = new IllegalStateException("status handler failure");
                owner.packetListener().setListener(ConnectionState.STATUS, StatusRequestPacket.class, (_, _) -> {
                    throw failure;
                });

                byte[] bytes = malformed
                        ? NetworkBuffer.makeArray(buffer -> buffer.write(NetworkBuffer.VAR_INT, ServerFlag.MAX_PACKET_SIZE_PRE_AUTH + 1))
                        : new byte[]{1, 0}; // Framed status request: one byte payload, packet ID zero.
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) client.write(buffer);
                client.shutdownOutput();
                try {
                    while (connection.isOnline() && ownerErrors.isEmpty()) connection.read(owner.server().packetParser());
                    assertTrue(defaultErrors.isEmpty());
                    assertEquals(reportedCount, ownerErrors.size());
                    assertEquals(staysOnline, connection.isOnline());
                    if (reportedCount > 0) {
                        if (malformed) assertInstanceOf(DataFormatException.class, ownerErrors.getFirst());
                        else assertSame(failure, ownerErrors.getFirst());
                    }
                } finally {
                    connection.disconnect();
                    connection.cleanup();
                }
            }
        }
    }
}
