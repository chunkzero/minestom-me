package net.minestom.server;

import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.server.registry.Registries;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.Difficulty;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerProcessIsolationTest {
    @SuppressWarnings("removal") // Default-process bridge pending ownership migration.
    @Test
    void constructionDoesNotReplaceDefaultProcess() throws IOException {
        final var defaultProcess = MinecraftServer.process();
        try (var processes = new ServerProcessPair();
             var channel = SocketChannel.open()) {
            var first = processes.first();
            var second = processes.second();
            assertSame(defaultProcess, MinecraftServer.process());
            assertNotSame(first.registries(), second.registries());
            assertNotSame(first.command(), second.command());
            assertNotSame(first.eventHandler(), second.eventHandler());
            assertNotSame(first.scheduler(), second.scheduler());
            assertSame(first, first.connection().process());
            assertSame(second, second.instance().process());
            assertSame(second, second.server().process());

            var connection = new PlayerSocketConnection(second, channel,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Thread.currentThread(), Thread.currentThread());
            assertSame(second, connection.process());
            assertSame(defaultProcess, MinecraftServer.process());
        }
    }

    @SuppressWarnings("removal") // Default-process bridge pending ownership migration.
    @Test
    void settingsBelongToTheirProcessAndStaticAccessUsesTheDefault() {
        try (var first = MinecraftServer.updateProcess();
             var second = ServerProcess.create()) {
            MinecraftServer.setBrandName("First");
            MinecraftServer.setDifficulty(Difficulty.HARD);
            MinecraftServer.setCompressionThreshold(0);
            second.setBrandName("Second");
            second.setDifficulty(Difficulty.PEACEFUL);
            second.setCompressionThreshold(128);

            assertEquals("First", first.brandName());
            assertEquals(Difficulty.HARD, first.difficulty());
            assertEquals(0, first.compressionThreshold());
            assertEquals("First", MinecraftServer.getBrandName());
            assertEquals(Difficulty.HARD, MinecraftServer.getDifficulty());
            assertEquals(0, MinecraftServer.getCompressionThreshold());
            first.setBrandName("Updated");
            assertEquals("Updated", MinecraftServer.getBrandName());
            assertEquals("Second", second.brandName());
            assertEquals(Difficulty.PEACEFUL, second.difficulty());
            assertEquals(128, second.compressionThreshold());

            try (var replacement = MinecraftServer.updateProcess()) {
                assertEquals("Minestom", replacement.brandName());
                assertEquals(Difficulty.NORMAL, replacement.difficulty());
                assertEquals(256, replacement.compressionThreshold());
                assertEquals("Second", second.brandName());
            }
        }
    }

    @Test
    void closedProcessCannotStart() {
        var process = ServerProcess.create();
        process.close();
        assertDoesNotThrow(process::close);
        assertThrows(IllegalStateException.class, () ->
                process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)));
        assertFalse(process.isAlive());
        assertFalse(process.server().isOpen());
    }

    @Test
    void startingOneProcessDoesNotFreezeAnother() {
        final boolean insideTest = ServerFlag.INSIDE_TEST;
        ServerFlag.INSIDE_TEST = false;
        try (var first = ServerProcess.create()) {
            first.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            assertTrue(first.registries().dimensionType().isFrozen());
            assertThrows(UnsupportedOperationException.class, () ->
                    first.registries().dimensionType().register("test:frozen", DimensionType.builder().build()));
            assertThrows(IllegalStateException.class, () -> first.setCompressionThreshold(64));

            try (var second = ServerProcess.create()) {
                var dimension = DimensionType.builder().ambientLight(0.5f).build();
                var key = second.registries().dimensionType().register("test:second", dimension);
                assertSame(dimension, second.registries().dimensionType().get(key));
                assertNull(first.registries().dimensionType().get(key));
                assertFalse(second.registries().dimensionType().isFrozen());
                assertDoesNotThrow(() -> Registries.vanilla().dimensionType()
                        .register("test:standalone", dimension));
            }
        } finally {
            ServerFlag.INSIDE_TEST = insideTest;
        }
    }
}
