package net.minestom.server.timer;

import net.minestom.server.ServerProcess;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minestom.server.timer.SchedulerLifecycleTest.await;
import static net.minestom.server.timer.TestScheduler.awaitTimer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class ProcessSchedulerTest {
    @Test
    void closingOneProcessCancelsItsDelayedAndRepeatingWorkOnly() throws Exception {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first().schedulerManager();
            var second = pair.second().schedulerManager();
            var firstCalls = new AtomicInteger();
            var secondCalls = new AtomicInteger();
            var firstRepeat = first.scheduleTask(firstCalls::incrementAndGet, TaskSchedule.immediate(), TaskSchedule.hours(1));
            var secondRepeat = second.scheduleTask(secondCalls::incrementAndGet, TaskSchedule.immediate(), TaskSchedule.millis(1));
            first.process();
            second.process();
            var delayed = first.buildTask(firstCalls::incrementAndGet).delay(TaskSchedule.hours(1)).schedule();
            var delayedHandle = ((TaskImpl) delayed).pending;
            assertNotNull(delayedHandle);
            pair.first().close();
            assertTrue(delayedHandle.isCancelled());
            assertFalse(firstRepeat.isAlive());
            assertFalse(delayed.isAlive());
            first.processTick();
            first.processTickEnd();
            assertEquals(1, firstCalls.get());

            awaitTimer(secondRepeat);
            second.process();
            assertTrue(secondCalls.get() >= 2);
            assertTrue(secondRepeat.isAlive());
            secondRepeat.cancel();
            var secondDelay = second.buildTask(secondCalls::incrementAndGet).delay(TaskSchedule.millis(1)).schedule();
            awaitTimer(secondDelay);
            int before = secondCalls.get();
            second.process();
            assertEquals(before + 1, secondCalls.get());
            assertFalse(secondDelay.isAlive());
            assertThrows(RejectedExecutionException.class, () -> first.scheduleNextTick(() -> {}));
            assertThrows(RejectedExecutionException.class, () -> Scheduler.newScheduler(pair.first()));
        }
    }

    @Test
    void scheduledAndShutdownFailuresReachTheOwnerAndDoNotSkipOtherTasks() {
        try (var pair = new ServerProcessPair()) {
            var firstErrors = new ArrayList<Throwable>();
            var secondErrors = new ArrayList<Throwable>();
            pair.first().exceptionManager().setExceptionHandler(firstErrors::add);
            pair.second().exceptionManager().setExceptionHandler(secondErrors::add);
            var expected = new IllegalStateException("scheduled failure");
            var completed = new AtomicInteger();
            for (var process : List.of(pair.first(), pair.second())) {
                var entity = new Entity(process, EntityType.ZOMBIE);
                var instance = process.instanceManager().createInstanceContainer(ChunkLoader.noop());
                for (var scheduler : List.of(process.schedulerManager(), entity.scheduler(), instance.scheduler())) {
                    scheduler.scheduleNextTick(() -> { throw expected; });
                    scheduler.scheduleNextTick(completed::incrementAndGet);
                    scheduler.processTick();
                }
            }
            assertEquals(6, completed.get());
            assertEquals(List.of(expected, expected, expected), firstErrors.stream().map(Throwable::getCause).toList());
            assertEquals(List.of(expected, expected, expected), secondErrors.stream().map(Throwable::getCause).toList());

            var shutdownFailure = new IllegalArgumentException("shutdown failure");
            pair.first().schedulerManager().buildShutdownTask(() -> { throw shutdownFailure; });
            pair.first().schedulerManager().buildShutdownTask(completed::incrementAndGet);
            pair.first().close();
            pair.first().close();
            assertEquals(7, completed.get());
            assertSame(shutdownFailure, firstErrors.getLast().getCause());
            assertEquals(3, secondErrors.size());
            assertFalse(pair.second().schedulerManager().isClosed());
        }
    }

    @Test
    void processCloseIncludesUnplacedEntitiesAndUnregisteredInstances() {
        try (var pair = new ServerProcessPair()) {
            var entity = new Entity(pair.first(), EntityType.ZOMBIE);
            var instance = new InstanceContainer(pair.first(), UUID.randomUUID(), DimensionType.OVERWORLD, ChunkLoader.noop(), DimensionType.OVERWORLD.key());
            var otherEntity = new Entity(pair.second(), EntityType.ZOMBIE);
            var calls = new AtomicInteger();
            var entityTask = entity.scheduler().scheduleNextTick(calls::incrementAndGet);
            var instanceTask = instance.scheduler().submitTask(TaskSchedule::park);
            var otherTask = otherEntity.scheduler().scheduleNextTick(calls::incrementAndGet);
            assertTrue(pair.first().instanceManager().getInstances().isEmpty());
            pair.first().close();
            assertTrue(entity.scheduler().isClosed());
            assertTrue(instance.scheduler().isClosed());
            assertFalse(entityTask.isAlive());
            assertFalse(instanceTask.isAlive());
            instanceTask.unpark();
            entity.scheduler().processTick();
            instance.scheduler().processTick();
            assertEquals(0, calls.get());
            assertTrue(otherTask.isAlive());
            otherEntity.scheduler().processTick();
            assertEquals(1, calls.get());
        }
    }

    @Test
    void movementAndTemporaryRemovalPreserveWorkWhilePermanentRemovalDisposesIt() {
        try (var pair = new ServerProcessPair()) {
            var process = pair.first();
            var source = process.instanceManager().createInstanceContainer(ChunkLoader.noop());
            var destination = process.instanceManager().createInstanceContainer(ChunkLoader.noop());
            var entity = new ReusableEntity(process);
            entity.setAutoViewable(false);
            entity.setInstance(source).join();
            var calls = new AtomicInteger();
            var task = entity.scheduler().scheduleTask(calls::incrementAndGet, TaskSchedule.nextTick(), TaskSchedule.nextTick());
            entity.setInstance(destination).join();
            entity.scheduler().processTick();
            assertEquals(1, calls.get());
            entity.removeTemporarily();
            assertFalse(entity.scheduler().isClosed());
            assertTrue(task.isAlive());
            entity.remove(); // Permanent disposal must work even after temporary removal.
            assertTrue(entity.scheduler().isClosed());
            assertFalse(task.isAlive());
            entity.scheduler().processTick();
            assertEquals(1, calls.get());
            assertThrows(RejectedExecutionException.class, () -> entity.scheduler().scheduleNextTick(() -> {}));
            assertFalse(source.scheduler().isClosed());
            assertFalse(destination.scheduler().isClosed());
        }
    }

    @Test
    void instanceUnregistrationClosesItsSchedulerAndRemovedEntityWork() {
        try (var pair = new ServerProcessPair()) {
            var process = pair.first();
            var instance = process.instanceManager().createInstanceContainer(ChunkLoader.noop());
            var entity = Entity.builder(EntityType.ZOMBIE).autoViewable(false).spawn(instance).join();
            var instanceTask = instance.scheduler().submitTask(TaskSchedule::park);
            var entityTask = entity.scheduler().submitTask(TaskSchedule::park);
            process.instanceManager().unregisterInstance(instance);
            assertTrue(instance.scheduler().isClosed());
            assertTrue(entity.scheduler().isClosed());
            assertFalse(instanceTask.isAlive());
            assertFalse(entityTask.isAlive());
            assertThrows(IllegalStateException.class, () -> process.instanceManager().registerInstance(instance));
            assertFalse(instance.isRegistered());
            assertTrue(process.instanceManager().getInstances().isEmpty());
            assertFalse(process.schedulerManager().isClosed());
        }
    }

    @Test
    void shutdownCallbacksSeeClosedSchedulersAndCannotRegisterMoreWork() throws Exception {
        try (var pair = new ServerProcessPair(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduler = pair.first().schedulerManager();
            var child = Scheduler.newScheduler(pair.first());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var task = child.submitTask(TaskSchedule::park);
            scheduler.buildShutdownTask(() -> {
                entered.countDown();
                await(release);
                scheduler.close(); // Reentrant shutdown must not wait on itself.
            });
            var closing = executor.submit(pair.first()::close);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(task.isAlive());
                assertTrue(child.isClosed());
                assertThrows(RejectedExecutionException.class, () -> scheduler.buildShutdownTask(() -> {}));
                assertThrows(RejectedExecutionException.class, scheduler::createScheduler);
                assertThrows(RejectedExecutionException.class, () -> scheduler.execute(() -> {}));
                var calls = new AtomicInteger();
                pair.second().schedulerManager().scheduleNextTick(calls::incrementAndGet);
                pair.second().schedulerManager().processTick();
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
            }
            closing.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void scheduledCallbackCanCloseRunningProcessWithoutDeadlock() throws Exception {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var closed = new CountDownLatch(1);
            var task = first.schedulerManager().scheduleNextTick(() -> {
                first.close();
                closed.countDown();
            });
            first.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertFalse(first.isAlive());
            assertFalse(task.isAlive());
            pair.second().ticker().tick(System.nanoTime());
            assertTrue(pair.second().dispatcher().isAlive());
        }
    }

    @Test
    void entityScheduledCallbackCanCloseProcessDuringDispatch() throws InterruptedException {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var instance = first.instanceManager().createInstanceContainer(ChunkLoader.noop());
            var entity = Entity.builder(EntityType.ZOMBIE).autoViewable(false).spawn(instance).join();
            var task = entity.scheduler().scheduleNextTick(first::close);
            first.ticker().tick(System.nanoTime());
            assertFalse(task.isAlive());
            assertTrue(entity.scheduler().isClosed());
            for (var thread : first.dispatcher().threads()) {
                thread.join(5000);
                assertFalse(thread.isAlive());
            }
            pair.second().ticker().tick(System.nanoTime());
            assertTrue(pair.second().dispatcher().isAlive());
        }
    }

    private static final class ReusableEntity extends Entity {
        ReusableEntity(ServerProcess process) {
            super(process, EntityType.ZOMBIE);
        }

        void removeTemporarily() {
            remove(false);
        }
    }
}
