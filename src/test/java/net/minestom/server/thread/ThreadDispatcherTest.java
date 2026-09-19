package net.minestom.server.thread;

import net.minestom.server.ProcessOwned;
import net.minestom.server.ServerProcess;
import net.minestom.server.Tickable;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ThreadDispatcherTest {
    @Test
    @Timeout(10)
    void processDispatcherRejectsForeignOwnedPartitionsAndElements() throws InterruptedException {
        record OwnedTickable(ServerProcess process, AtomicInteger ticks) implements Tickable, ProcessOwned {
            @Override public void tick(long time) { ticks.incrementAndGet(); }
        }
        try (var pair = new ServerProcessPair()) {
            var local = new OwnedTickable(pair.first(), new AtomicInteger());
            var foreign = new OwnedTickable(pair.second(), new AtomicInteger());
            ThreadDispatcher<Object, Tickable> dispatcher = ThreadDispatcher.dispatcher(pair.first(), ThreadProvider.counter(), 1);
            try {
                assertThrows(IllegalArgumentException.class, () -> dispatcher.createPartition(foreign));
                dispatcher.createPartition(local);
                assertThrows(IllegalArgumentException.class, () -> dispatcher.updateElement(foreign, local));
                assertThrows(IllegalArgumentException.class, () -> dispatcher.updateElement(local, foreign));
                var unowned = new AtomicInteger();
                dispatcher.updateElement(_ -> unowned.incrementAndGet(), local);
                dispatcher.start();
                dispatcher.updateAndAwait(0);
                assertEquals(1, local.ticks().get());
                assertEquals(1, unowned.get());
                assertEquals(0, foreign.ticks().get());
            } finally {
                dispatcher.shutdown();
                for (var thread : dispatcher.threads()) thread.join();
            }
        }
    }

    @Test
    @Timeout(10)
    void customDispatcherAcceptsOwnedChunksAndEntities() throws InterruptedException {
        try (var process = ServerProcess.create()) {
            var instance = process.instanceManager().createInstanceContainer(ChunkLoader.noop());
            var chunk = instance.loadChunk(0, 0).join();
            var ticks = new AtomicInteger();
            var entity = new Entity(process, EntityType.ZOMBIE) {
                @Override
                public void tick(long time) {
                    ticks.incrementAndGet();
                }
            };
            ThreadDispatcher<Chunk, Entity> dispatcher = ThreadDispatcher.singleThread();
            try {
                dispatcher.createPartition(chunk);
                dispatcher.updateElement(entity, chunk);
                dispatcher.start();
                dispatcher.updateAndAwait(System.nanoTime());
                assertEquals(1, ticks.get());
                assertSame(dispatcher.threads().getFirst(), entity.acquirable().assignedThread());
                dispatcher.removeElement(entity);
                dispatcher.deletePartition(chunk);
                dispatcher.updateAndAwait(System.nanoTime());
                assertEquals(1, ticks.get());
            } finally {
                dispatcher.shutdown();
                for (var thread : dispatcher.threads()) thread.join();
            }
        }
    }

    @Test
    @Timeout(10)
    void repeatedShutdownReleasesOnlyTheUnstartedWorkersShareOfATick() throws InterruptedException {
        ThreadDispatcher<Integer, Tickable> dispatcher = ThreadDispatcher.dispatcher(partition -> partition, 2);
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var complete = new CountDownLatch(1);
        Thread coordinator = null;
        try {
            dispatcher.createPartition(0);
            dispatcher.createPartition(1);
            dispatcher.updateElement(_ -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }, 1);
            dispatcher.threads().get(1).start();
            coordinator = Thread.startVirtualThread(() -> {
                dispatcher.updateAndAwait(System.nanoTime());
                complete.countDown();
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            dispatcher.threads().getFirst().shutdown();
            dispatcher.threads().getFirst().shutdown();
            assertFalse(complete.await(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(complete.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            dispatcher.shutdown();
            if (coordinator != null) coordinator.join();
            for (var thread : dispatcher.threads()) thread.join();
        }
    }

    record World() {
    }

    static abstract class Element implements Tickable, AcquirableSource<Element> {
        final Acquirable<Element> acquirable = Acquirable.unassigned(this);

        @Override
        public Acquirable<? extends Element> acquirable() {
            return acquirable;
        }
    }

    @Test
    public void basic() {
        final class Counter implements Tickable {
            int value;

            @Override
            public void tick(long time) {
                value++;
            }
        }
        World world = new World();
        Counter element = new Counter();

        ThreadDispatcher<World, Counter> dispatcher = ThreadDispatcher.singleThread();
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();

        assertEquals(0, element.value);
        dispatcher.updateAndAwait(0);
        assertEquals(1, element.value);

        dispatcher.shutdown();
    }

    @Test
    public void basicAcquirable() {
        World world = new World();
        Element element = new Element() {
            @Override
            public void tick(long time) {
            }
        };

        ThreadDispatcher<World, Element> dispatcher = ThreadDispatcher.singleThread();
        dispatcher.createPartition(world);
        dispatcher.updateElement(element, world);
        dispatcher.start();

        assertNull(element.acquirable().assignedThread());
        dispatcher.updateAndAwait(0);
        assertNotNull(element.acquirable().assignedThread());

        dispatcher.shutdown();
    }

    @Test
    public void elementTick() {
        final AtomicInteger counter = new AtomicInteger();
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread();
        dispatcher.start();
        assertEquals(1, dispatcher.threads().size());
        assertThrows(Exception.class, () -> dispatcher.threads().add(new TickThread(1)));

        var partition = new World();
        Tickable element = (_) -> counter.incrementAndGet();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(element, partition);
        assertEquals(0, counter.get());

        dispatcher.updateAndAwait(System.nanoTime());
        dispatcher.updateElement(element, partition); // Should be ignored
        dispatcher.createPartition(partition); // Ignored too
        assertEquals(1, counter.get());

        dispatcher.updateAndAwait(System.nanoTime());
        assertEquals(2, counter.get());

        dispatcher.removeElement(element);
        dispatcher.updateAndAwait(System.nanoTime());
        assertEquals(2, counter.get());

        dispatcher.shutdown();
    }

    @Test
    public void elementTickLoop() {
        final AtomicInteger counter = new AtomicInteger();
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread();
        dispatcher.start();

        var partition = new World();
        Tickable element = (_) -> counter.incrementAndGet();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(element, partition);
        assertEquals(0, counter.get());

        for (int i = 0; i < 100; i++) {
            dispatcher.updateAndAwait(System.nanoTime());
            assertEquals(i + 1, counter.get());
        }

        dispatcher.shutdown();
    }

    @Test
    public void elementTickLoopAsync() {
        final AtomicInteger counter = new AtomicInteger();
        ThreadDispatcher<World, Tickable> dispatcher = ThreadDispatcher.singleThread();
        dispatcher.start();

        var partition = new World();
        Tickable element = (_) -> counter.incrementAndGet();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(element, partition);
        assertEquals(0, counter.get());

        final int count = 100;
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            Thread.startVirtualThread(() -> {
                dispatcher.updateAndAwait(System.nanoTime());
                latch.countDown();
            });
        }
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            fail("Latch was interrupted");
        }
        assertEquals(count, counter.get());

        dispatcher.shutdown();
    }

    @Test
    public void partitionTick() {
        // Partitions implementing Tickable should be ticked same as elements
        final AtomicInteger counter1 = new AtomicInteger();
        final AtomicInteger counter2 = new AtomicInteger();
        ThreadDispatcher<Tickable, Tickable> dispatcher = ThreadDispatcher.singleThread();
        dispatcher.start();
        assertEquals(1, dispatcher.threads().size());

        Tickable partition = (_) -> counter1.incrementAndGet();
        Tickable element = (_) -> counter2.incrementAndGet();
        dispatcher.createPartition(partition);
        dispatcher.updateElement(element, partition);
        assertEquals(0, counter1.get());
        assertEquals(0, counter2.get());

        for (int i = 0; i < 100; i++) {
            dispatcher.updateAndAwait(System.nanoTime());
            assertEquals(i + 1, counter1.get());
            assertEquals(i + 1, counter2.get());
        }

        dispatcher.deletePartition(partition);
        dispatcher.updateAndAwait(System.nanoTime());
        assertEquals(100, counter1.get());
        assertEquals(100, counter2.get());

        dispatcher.shutdown();
    }

    @Test
    public void uniqueThread() {
        // Ensure that partitions are properly dispatched across threads
        final int threadCount = 10;
        ThreadDispatcher<Tickable, Tickable> dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), threadCount);
        assertEquals(threadCount, dispatcher.threads().size());
        dispatcher.start();

        final AtomicInteger counter = new AtomicInteger();
        Set<Thread> threads = new CopyOnWriteArraySet<>();
        Set<Tickable> partitions = IntStream.range(0, threadCount)
                .mapToObj(_ -> (Tickable) (_) -> {
                    final Thread thread = Thread.currentThread();
                    assertInstanceOf(TickThread.class, thread);
                    assertEquals(1, ((TickThread) thread).entries.size());
                    assertTrue(threads.add(thread));
                    counter.getAndIncrement();
                })
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(threadCount, partitions.size());

        partitions.forEach(dispatcher::createPartition);
        assertEquals(0, counter.get());

        dispatcher.updateAndAwait(System.nanoTime());
        assertEquals(threadCount, counter.get());

        dispatcher.shutdown();
    }

    @Test
    public void threadUpdate() {
        // Ensure that partitions threads are properly updated every tick
        // when RefreshType.ALWAYS is used
        interface Updater extends Tickable {
            int getValue();
        }

        final int threadCount = 10;
        ThreadDispatcher<Updater, Tickable> dispatcher = ThreadDispatcher.dispatcher(new ThreadProvider<>() {
            @Override
            public int findThread(Updater partition) {
                return partition.getValue();
            }

            @Override
            public RefreshType refreshType() {
                return RefreshType.ALWAYS;
            }
        }, threadCount);
        assertEquals(threadCount, dispatcher.threads().size());
        dispatcher.start();

        Map<Updater, Thread> threads = new ConcurrentHashMap<>();
        Map<Updater, Thread> threads2 = new ConcurrentHashMap<>();
        Set<Updater> partitions = IntStream.range(0, threadCount)
                .mapToObj(value -> new Updater() {
                    private int v = value;

                    @Override
                    public int getValue() {
                        return v;
                    }

                    @Override
                    public void tick(long time) {
                        final Thread currentThread = Thread.currentThread();
                        assertInstanceOf(TickThread.class, currentThread);
                        if (threads.putIfAbsent(this, currentThread) == null) {
                            this.v = value + 1;
                        } else {
                            assertEquals(value + 1, v);
                            threads2.putIfAbsent(this, currentThread);
                        }
                    }
                }).collect(Collectors.toUnmodifiableSet());
        assertEquals(threadCount, partitions.size());

        partitions.forEach(dispatcher::createPartition);

        dispatcher.updateAndAwait(System.nanoTime());

        dispatcher.refreshThreads();

        dispatcher.updateAndAwait(System.nanoTime());

        assertEquals(threads2.size(), threads.size());
        assertNotEquals(threads, threads2, "Threads have not been updated at all");
        for (var entry : threads.entrySet()) {
            final Thread thread1 = entry.getValue();
            final Thread thread2 = threads2.get(entry.getKey());
            assertNotEquals(thread1, thread2);
        }

        dispatcher.shutdown();
    }
}
