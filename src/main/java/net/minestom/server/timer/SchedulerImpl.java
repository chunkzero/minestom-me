package net.minestom.server.timer;

import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class SchedulerImpl implements Scheduler {
    private static final AtomicInteger TASK_COUNTER = new AtomicInteger();

    private final SchedulerScope scope;
    // All task state and queues are guarded by this scheduler; callbacks run outside the lock.
    private final Set<TaskImpl> tasks = new HashSet<>();
    private final ArrayDeque<TaskImpl> tasksToExecute = new ArrayDeque<>();
    private final ArrayDeque<TaskImpl> tickEndTasksToExecute = new ArrayDeque<>();
    private final Int2ObjectAVLTreeMap<List<TaskImpl>> tickStartTaskQueue = new Int2ObjectAVLTreeMap<>();
    private final Int2ObjectAVLTreeMap<List<TaskImpl>> tickEndTaskQueue = new Int2ObjectAVLTreeMap<>();
    private int tickState;
    private volatile boolean closed;

    SchedulerImpl(SchedulerScope scope) {
        this.scope = scope;
    }

    @Override
    public void process() {
        processTickTasks(tickStartTaskQueue, tasksToExecute, 0);
    }

    @Override
    public void processTick() {
        processTickTasks(tickStartTaskQueue, tasksToExecute, 1);
    }

    @Override
    public void processTickEnd() {
        processTickTasks(tickEndTaskQueue, tickEndTasksToExecute, 0);
    }

    private void processTickTasks(Int2ObjectAVLTreeMap<List<TaskImpl>> tickQueue, ArrayDeque<TaskImpl> readyQueue, int tickDelta) {
        TaskImpl task;
        synchronized (this) {
            if (isClosed()) return;
            tickState += tickDelta;
            while (!tickQueue.isEmpty() && tickQueue.firstIntKey() <= tickState) {
                readyQueue.addAll(tickQueue.remove(tickQueue.firstIntKey()));
            }
            task = readyQueue.poll();
        }
        while (task != null) {
            handleTask(task);
            synchronized (this) {
                task = readyQueue.poll();
            }
        }
    }

    @Override
    public Task submitTask(Supplier<TaskSchedule> task, ExecutionType executionType) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(executionType);
        TaskImpl taskRef;
        synchronized (this) {
            if (isClosed()) throw new RejectedExecutionException("Scheduler is closed");
            taskRef = new TaskImpl(TASK_COUNTER.getAndIncrement(), task, executionType, this);
            tasks.add(taskRef);
        }
        handleTask(taskRef);
        return taskRef;
    }

    synchronized void unparkTask(TaskImpl task) {
        if (isClosed() || !task.alive || !task.parked) return;
        task.parked = false;
        enqueue(task);
    }

    private synchronized void enqueue(TaskImpl task) {
        if (isClosed() || !task.alive) return;
        task.pending = null;
        switch (task.executionType()) {
            case TICK_START -> tasksToExecute.add(task);
            case TICK_END -> tickEndTasksToExecute.add(task);
        }
    }

    private void handleTask(TaskImpl task) {
        Supplier<TaskSchedule> callback;
        synchronized (this) {
            if (isClosed() || !task.alive) return;
            callback = task.task;
            task.pending = null;
        }
        try {
            TaskSchedule schedule = Objects.requireNonNull(callback.get(), "Task schedule");
            synchronized (this) {
                if (isClosed() || !task.alive) return;
                switch (schedule) {
                    case TaskScheduleImpl.DurationSchedule duration ->
                            task.pending = scope.timer().schedule(() -> enqueue(task), duration.duration().toMillis(), TimeUnit.MILLISECONDS);
                    case TaskScheduleImpl.TickSchedule tick -> {
                        var queue = switch (task.executionType()) {
                            case TICK_START -> tickStartTaskQueue;
                            case TICK_END -> tickEndTaskQueue;
                        };
                        queue.computeIfAbsent(tickState + tick.tick(), _ -> new ArrayList<>()).add(task);
                    }
                    case TaskScheduleImpl.FutureSchedule future -> {
                        var stage = future.future().whenComplete((_, failure) -> {
                            if (failure == null) enqueue(task);
                            else cancelTask(task);
                        });
                        if (!stage.isDone()) task.pending = stage;
                    }
                    case TaskScheduleImpl.Park _ -> task.parked = true;
                    case TaskScheduleImpl.Stop _ -> cancelTask(task);
                    case TaskScheduleImpl.Immediate _ -> enqueue(task);
                }
            }
        } catch (Throwable t) {
            cancelTask(task);
            scope.handleException(new RuntimeException("Exception in scheduled task", t));
        }
    }

    synchronized void cancelTask(TaskImpl task) {
        if (!task.alive) return;
        task.alive = false;
        task.parked = false;
        task.task = null;
        if (task.pending != null) {
            task.pending.cancel(false);
            task.pending = null;
        }
        tasks.remove(task);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (var task : List.copyOf(tasks)) cancelTask(task);
            tasksToExecute.clear();
            tickEndTasksToExecute.clear();
            tickStartTaskQueue.clear();
            tickEndTaskQueue.clear();
        }
        scope.remove(this);
    }

    @Override
    public synchronized String toString() {
        return "SchedulerImpl[tasks=" + tasks.size() + ", tick=" + tickState + ", closed=" + isClosed() + ']';
    }

    @Override
    public boolean isClosed() {
        return closed || scope.isClosed();
    }
}
