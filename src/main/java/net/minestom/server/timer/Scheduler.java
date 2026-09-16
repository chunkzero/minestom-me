package net.minestom.server.timer;

import net.minestom.server.ServerProcess;
import net.minestom.server.exception.ExceptionHandler;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Represents a scheduler that will execute tasks with a precision based on its ticking rate.
 * If precision is important, consider using a JDK executor service or any third party library.
 * <p>
 * Tasks are by default executed in the caller thread.
 */
public sealed interface Scheduler extends Executor, AutoCloseable permits SchedulerImpl, SchedulerManager {
    /**
     * Creates a standalone scheduler with its own timer executor and stack-trace exception logging.
     * The caller must close it when no longer needed. No default server process is used.
     */
    static Scheduler newScheduler() {
        return newScheduler(Throwable::printStackTrace);
    }

    /** Creates a standalone scheduler with an explicit exception handler. The caller owns its lifetime. */
    static Scheduler newScheduler(ExceptionHandler exceptionHandler) {
        return new SchedulerScope(exceptionHandler, "Ms-StandaloneScheduler").newScheduler(true);
    }

    /** Creates a scheduler whose exceptions and lifetime belong to the given process. */
    static Scheduler newScheduler(ServerProcess process) {
        return process.scheduler().createScheduler();
    }

    /**
     * Cancels all tasks and rejects further submissions. Idempotent and safe inside a callback.
     * Callbacks already admitted to execution may finish, without interruption or rescheduling;
     * this method does not wait for them. Concurrent/reentrant calls do not wait for an ongoing close.
     * Processing a closed scheduler does nothing.
     */
    @Override
    void close();

    /** Whether closing this scheduler or its process has begun. */
    boolean isClosed();

    /**
     * Process scheduled tasks based on time to increase scheduling precision.
     * <p>
     * This method is not thread-safe.
     */
    void process();

    /**
     * Advance 1 tick and call {@link #process()}.
     * <p>
     * This method is not thread-safe.
     */
    void processTick();

    /**
     * Execute tasks set to run at the end of this tick.
     * <p>
     * This method is not thread-safe.
     */
    void processTickEnd();

    /**
     * Submits a new task with custom scheduling logic.
     * <p>
     * This is the primitive method used by all scheduling shortcuts,
     * {@code task} is immediately executed in the caller thread to retrieve its scheduling state
     * and the task will stay alive as long as {@link TaskSchedule#stop()} is not returned (or {@link Task#cancel()} is called).
     *
     * @param task          the task to be directly executed in the caller thread
     * @param executionType the execution type
     * @return the created task
     * @throws RejectedExecutionException if the scheduler or its process has closed
     */
    Task submitTask(Supplier<TaskSchedule> task, ExecutionType executionType);

    default Task submitTask(Supplier<TaskSchedule> task) {
        return submitTask(task, ExecutionType.TICK_START);
    }

    default Task.Builder buildTask(Runnable task) {
        return new Task.Builder(this, task);
    }

    default Task scheduleTask(Runnable task,
                                       TaskSchedule delay, TaskSchedule repeat,
                                       ExecutionType executionType) {
        return buildTask(task).delay(delay).repeat(repeat).executionType(executionType).schedule();
    }

    default Task scheduleTask(Runnable task, TaskSchedule delay, TaskSchedule repeat) {
        return scheduleTask(task, delay, repeat, ExecutionType.TICK_START);
    }

    default Task scheduleTask(Supplier<TaskSchedule> task, TaskSchedule delay) {
        return new Task.Builder(this, task).delay(delay).schedule();
    }

    default Task scheduleNextTick(Runnable task, ExecutionType executionType) {
        return buildTask(task).delay(TaskSchedule.nextTick()).executionType(executionType).schedule();
    }

    default Task scheduleNextTick(Runnable task) {
        return scheduleNextTick(task, ExecutionType.TICK_START);
    }

    default Task scheduleEndOfTick(Runnable task) {
        return scheduleNextProcess(task, ExecutionType.TICK_END);
    }

    default Task scheduleNextProcess(Runnable task, ExecutionType executionType) {
        return buildTask(task).delay(TaskSchedule.immediate()).executionType(executionType).schedule();
    }

    default Task scheduleNextProcess(Runnable task) {
        return scheduleNextProcess(task, ExecutionType.TICK_START);
    }

    /**
     * Implementation of {@link Executor}, proxies to {@link #scheduleNextTick(Runnable)}.
     * @param command the task to execute on the next tick
     */
    @Override
    default void execute(Runnable command) {
        scheduleNextTick(command);
    }
}
