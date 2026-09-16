package net.minestom.server.timer;

import net.minestom.server.ServerProcess;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Owns the server scheduler, its child schedulers, and a single process-local timer executor. */
public final class SchedulerManager implements Scheduler {
    private final SchedulerScope scope;
    private final Scheduler scheduler;
    private final ArrayDeque<Runnable> shutdownTasks = new ArrayDeque<>();
    private boolean closed;

    public SchedulerManager(ServerProcess process) {
        Objects.requireNonNull(process);
        this.scope = new SchedulerScope(process.exception()::handleException, "Ms-Scheduler-" + process.id());
        this.scheduler = scope.newScheduler(false);
    }

    /** Creates an independently ticked scheduler closed by this manager, even if its object is unregistered. */
    public Scheduler createScheduler() {
        return scope.newScheduler(false);
    }

    @Override
    public void process() {
        this.scheduler.process();
    }

    @Override
    public void processTick() {
        this.scheduler.processTick();
    }

    @Override
    public void processTickEnd() {
        this.scheduler.processTickEnd();
    }

    @Override
    public Task submitTask(Supplier<TaskSchedule> task, ExecutionType executionType) {
        return scheduler.submitTask(task, executionType);
    }

    /** Alias for {@link #close()}. */
    public void shutdown() {
        close();
    }

    /**
     * Closes all owned schedulers and the timer executor, then runs shutdown callbacks once in registration order.
     * Callbacks run outside locks; a failure is reported to the process and does not skip later callbacks.
     */
    @Override
    public void close() {
        List<Runnable> callbacks;
        synchronized (this) {
            if (closed) return;
            closed = true;
            callbacks = List.copyOf(shutdownTasks);
            shutdownTasks.clear();
        }
        scope.close();
        for (var callback : callbacks) {
            try {
                callback.run();
            } catch (Throwable t) {
                scope.handleException(new RuntimeException("Exception in shutdown task", t));
            }
        }
    }

    @Override
    public boolean isClosed() {
        return scope.isClosed();
    }

    /** Registers a shutdown callback, or rejects it if shutdown has begun. */
    public synchronized void buildShutdownTask(Runnable runnable) {
        if (closed) throw new RejectedExecutionException("Scheduler manager is closed");
        shutdownTasks.add(Objects.requireNonNull(runnable));
    }
}
