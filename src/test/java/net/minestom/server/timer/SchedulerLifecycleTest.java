package net.minestom.server.timer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minestom.server.timer.TestScheduler.awaitTimer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(15)
class SchedulerLifecycleTest {
    @Test
    void cancellationAndCloseDisableEveryWakeupPath() throws Exception {
        for (boolean close : List.of(false, true)) {
            try (var scheduler = Scheduler.newScheduler()) {
                var calls = new AtomicInteger();
                var future = new CompletableFuture<Void>();
                var tasks = new ArrayList<Task>();
                for (var type : ExecutionType.values()) {
                    for (var delay : List.of(TaskSchedule.hours(1), TaskSchedule.tick(1), TaskSchedule.immediate(),
                            TaskSchedule.park(), TaskSchedule.future(future), TaskSchedule.millis(0))) {
                        var task = scheduler.buildTask(calls::incrementAndGet).delay(delay).executionType(type).schedule();
                        tasks.add(task);
                        if (delay instanceof TaskScheduleImpl.DurationSchedule duration && duration.duration().isZero()) {
                            awaitTimer(task); // Also cover a timer callback which has already enqueued its task.
                        }
                    }
                }
                var timer = ((TaskImpl) tasks.getFirst()).pending;
                assertNotNull(timer);
                if (close) closeScheduler(scheduler);
                else tasks.forEach(Task::cancel);
                assertTrue(timer.isCancelled());
                assertFalse(future.isCancelled(), "The caller still owns the source future");
                future.complete(null);
                tasks.forEach(Task::unpark);
                scheduler.processTick();
                scheduler.processTickEnd();
                assertEquals(0, calls.get());
                tasks.forEach(task -> {
                    assertFalse(task.isAlive());
                    assertFalse(task.isParked());
                });
            }
        }
    }

    @Test
    void unparkAndCompletedFutureRespectExecutionPhase() {
        try (var scheduler = Scheduler.newScheduler()) {
            var calls = new AtomicInteger();
            var parked = scheduler.buildTask(calls::incrementAndGet)
                    .delay(TaskSchedule.park()).executionType(ExecutionType.TICK_END).schedule();
            scheduler.buildTask(calls::incrementAndGet)
                    .delay(TaskSchedule.future(CompletableFuture.completedFuture(null)))
                    .executionType(ExecutionType.TICK_END).schedule();
            parked.unpark();
            parked.unpark();
            scheduler.processTick();
            assertEquals(0, calls.get());
            scheduler.processTickEnd();
            assertEquals(2, calls.get());
            scheduler.processTickEnd();
            assertEquals(2, calls.get());
        }
    }

    @Test
    void inFlightSubmissionMayFinishButCannotRescheduleAfterClose() throws Exception {
        try (var scheduler = Scheduler.newScheduler(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var calls = new AtomicInteger();
            var future = new CompletableFuture<Void>();
            var submission = executor.submit(() -> scheduler.submitTask(() -> {
                calls.incrementAndGet();
                entered.countDown();
                await(release);
                return TaskSchedule.future(future);
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                closeScheduler(scheduler);
                assertTrue(scheduler.isClosed());
                assertFalse(submission.isDone(), "Close must not wait for the callback");
                assertThrows(RejectedExecutionException.class, () -> scheduler.submitTask(() -> {
                    fail("Rejected submissions must not execute their supplier");
                    return TaskSchedule.stop();
                }));
                future.complete(null);
            } finally {
                release.countDown();
            }
            var task = submission.get(5, TimeUnit.SECONDS);
            assertFalse(task.isAlive());
            scheduler.processTick();
            assertEquals(1, calls.get());
        }
    }

    @Test
    void futureCompletionUnparkAndSubmissionCanRaceClose() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 30; i++) {
                try (var scheduler = Scheduler.newScheduler()) {
                    var gate = new CountDownLatch(1);
                    var calls = new AtomicInteger();
                    var future = new CompletableFuture<Void>();
                    var waiting = scheduler.buildTask(calls::incrementAndGet).delay(TaskSchedule.future(future)).schedule();
                    var parked = scheduler.buildTask(calls::incrementAndGet).delay(TaskSchedule.park()).schedule();
                    var completion = executor.submit(() -> {
                        await(gate);
                        future.complete(null);
                        parked.unpark();
                        try {
                            return scheduler.scheduleNextTick(calls::incrementAndGet);
                        } catch (RejectedExecutionException _) {
                            return null;
                        }
                    });
                    var closing = executor.submit(() -> {
                        await(gate);
                        closeScheduler(scheduler);
                    });
                    gate.countDown();
                    var submitted = completion.get(5, TimeUnit.SECONDS);
                    closing.get(5, TimeUnit.SECONDS);
                    if (submitted != null) assertFalse(submitted.isAlive());
                    assertFalse(waiting.isAlive());
                    assertFalse(parked.isAlive());
                    scheduler.processTick();
                    scheduler.processTickEnd();
                    assertEquals(0, calls.get());
                }
            }
        }
    }

    @Test
    void scopeCancelsTimerHandlesAndTerminatesExecutor() throws Exception {
        try (var scope = new SchedulerScope(_ -> fail("Unexpected task failure"), "test-scheduler")) {
            var scheduler = scope.newScheduler(false);
            var task = scheduler.buildTask(() -> fail("Cancelled timer ran")).delay(TaskSchedule.hours(1)).schedule();
            var pending = ((TaskImpl) task).pending;
            assertNotNull(pending);
            task.cancel();
            assertTrue(pending.isCancelled());
            assertTrue(scope.timer().getQueue().isEmpty());
            closeScope(scope);
            assertTrue(scope.timer().awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(scheduler.isClosed());
        }
    }

    private static void closeScheduler(Scheduler scheduler) {
        scheduler.close();
        assertTrue(scheduler.isClosed());
    }

    private static void closeScope(SchedulerScope scope) {
        scope.close();
        assertTrue(scope.isClosed());
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
