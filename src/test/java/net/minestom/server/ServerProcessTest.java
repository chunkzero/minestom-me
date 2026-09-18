package net.minestom.server;

import net.minestom.server.timer.TaskSchedule;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class ServerProcessTest {
    @Test
    void secondStartIsRejectedWithoutStoppingTheServer() {
        try (var process = ServerProcess.create()) {
            assertFalse(process.server().isOpen());
            process.start(loopback());
            assertThrows(IllegalStateException.class, () -> process.start(loopback()));
            assertTrue(process.isAlive());
            assertTrue(process.server().isOpen());
            assertDoesNotThrow(() -> process.ticker().tick(System.nanoTime()));
        }
    }

    @Test
    void failedBindClosesSchedulersAndAlreadyStartedWorkers() throws Exception {
        try (var pair = new ServerProcessPair(); var occupied = ServerSocketChannel.open().bind(loopback())) {
            var first = pair.first();
            var delayed = first.scheduler().buildTask(() -> {}).delay(TaskSchedule.hours(1)).schedule();
            first.ticker().tick(System.nanoTime());
            assertTrue(first.dispatcher().isAlive());
            var shutdowns = new AtomicInteger();
            first.scheduler().buildShutdownTask(shutdowns::incrementAndGet);
            assertThrows(RuntimeException.class, () -> first.start(occupied.getLocalAddress()));
            assertFalse(first.isAlive());
            assertFalse(first.server().isOpen());
            assertFalse(first.dispatcher().isAlive());
            assertTrue(first.scheduler().isClosed());
            assertFalse(delayed.isAlive());
            assertEquals(1, shutdowns.get());
            first.close();
            assertEquals(1, shutdowns.get());
            assertThrows(IllegalStateException.class, () -> first.start(loopback()));
            pair.second().start(loopback());
            assertTrue(pair.second().server().isOpen());
        }
    }

    @Test
    void invalidStartupAlsoClosesTheProcess() {
        try (var process = ServerProcess.create()) {
            assertThrows(NullPointerException.class, () -> process.start(null));
            assertTrue(process.scheduler().isClosed());
            assertFalse(process.isAlive());
            assertThrows(IllegalStateException.class, () -> process.start(loopback()));
        }
    }

    @Test
    void throwingShutdownAndExceptionCallbacksDoNotSkipCleanup() throws Exception {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var laterCallback = new AtomicInteger();
            var handlerFailure = new IllegalStateException("exception handler failed");
            first.exception().setExceptionHandler(_ -> { throw handlerFailure; });
            first.scheduler().buildShutdownTask(() -> { throw new IllegalArgumentException("shutdown failed"); });
            first.scheduler().buildShutdownTask(laterCallback::incrementAndGet);
            first.start(loopback());
            var address = first.server().socketAddress();
            assertSame(handlerFailure, assertThrows(IllegalStateException.class, first::close));
            assertEquals(1, laterCallback.get());
            assertTrue(first.scheduler().isClosed());
            assertFalse(first.server().isOpen());
            assertFalse(first.dispatcher().isAlive());
            assertDoesNotThrow(first::close);
            try (var rebound = ServerSocketChannel.open().bind(address)) {
                assertTrue(rebound.isOpen());
            }
            pair.second().ticker().tick(System.nanoTime());
            assertTrue(pair.second().dispatcher().isAlive());
        }
    }

    private static InetSocketAddress loopback() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }
}
