package net.minestom.server.extras.lan;

import net.kyori.adventure.text.Component;
import net.minestom.server.ping.ServerListPingType;
import net.minestom.server.ping.Status;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessLanTest {
    @Test
    void advertisementsAndSocketCleanupBelongToTheirProcess() throws Exception {
        final int port;
        try (var socket = new DatagramSocket(0)) {
            port = socket.getLocalPort();
        }
        try (var pair = new ServerProcessPair()) {
            var first = pair.first().lan();
            var second = pair.second().lan();
            assertTrue(first.open(new OpenToLANConfig().port(port)));
            assertFalse(first.open());
            assertTrue(second.open(new OpenToLANConfig().port(0)));
            assertThrows(SocketException.class, () -> new DatagramSocket(port));
            pair.first().close();
            assertFalse(first.isOpen());
            assertFalse(first.close());
            assertTrue(second.isOpen());
            assertThrows(RejectedExecutionException.class, () -> first.open(new OpenToLANConfig().port(port)));
            try (var rebound = new DatagramSocket(port)) {
                assertEquals(port, rebound.getLocalPort());
            }
            assertTrue(second.close());
        }
    }

    @Test
    void lanResponsesRequireTheOwningServersPort() {
        var status = Status.builder().description(Component.text("World")).build();
        assertThrows(IllegalStateException.class, () -> ServerListPingType.OPEN_TO_LAN.getPingResponse(status));
        assertEquals("[MOTD]World[/MOTD][AD]25565[/AD]", ServerListPingType.OPEN_TO_LAN.getPingResponse(status, 25565));
        assertEquals("[MOTD]World[/MOTD][AD]25566[/AD]", ServerListPingType.OPEN_TO_LAN.getPingResponse(status, 25566));
    }
}
