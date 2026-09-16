package net.minestom.server.timer;

import net.minestom.server.exception.ExceptionHandler;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Owns timer resources and tracks schedulers independently of entity placement or instance registration. */
final class SchedulerScope implements AutoCloseable {
    private final ExceptionHandler exceptionHandler;
    private final ScheduledThreadPoolExecutor timer;
    // Tracking must not keep otherwise unreachable entities/instances and their callbacks alive.
    private final Set<SchedulerImpl> schedulers = Collections.newSetFromMap(new WeakHashMap<>());
    private volatile boolean closed;

    SchedulerScope(ExceptionHandler exceptionHandler, String threadName) {
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler);
        this.timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name(threadName).factory());
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    synchronized SchedulerImpl newScheduler(boolean ownsScope) {
        if (closed) throw new RejectedExecutionException("Scheduler owner is closed");
        var scheduler = new SchedulerImpl(this, ownsScope);
        schedulers.add(scheduler);
        return scheduler;
    }

    synchronized void remove(SchedulerImpl scheduler) {
        schedulers.remove(scheduler);
    }

    ScheduledThreadPoolExecutor timer() {
        return timer;
    }

    void handleException(Throwable throwable) {
        exceptionHandler.handleException(throwable);
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        List<SchedulerImpl> owned;
        synchronized (this) {
            if (closed) return;
            closed = true;
            owned = List.copyOf(schedulers);
            schedulers.clear();
        }
        for (var scheduler : owned) scheduler.close();
        timer.shutdownNow();
    }
}
