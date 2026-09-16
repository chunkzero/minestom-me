package net.minestom.server.timer;

import it.unimi.dsi.fastutil.HashCommon;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Future;
import java.util.function.Supplier;

final class TaskImpl implements Task {
    private final int id;
    private final ExecutionType executionType;
    private final SchedulerImpl owner;

    volatile boolean alive;
    volatile boolean parked;
    @Nullable Supplier<TaskSchedule> task;
    @Nullable Future<?> pending;

    TaskImpl(int id,
             Supplier<TaskSchedule> task,
             ExecutionType executionType,
             SchedulerImpl owner) {
        this.id = id;
        this.task = task;
        this.executionType = executionType;
        this.owner = owner;
        this.alive = true;
    }

    @Override
    public void unpark() {
        this.owner.unparkTask(this);
    }

    @Override
    public boolean isParked() {
        return parked;
    }

    @Override
    public void cancel() {
        this.owner.cancelTask(this);
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public ExecutionType executionType() {
        return executionType;
    }

    @Override
    public SchedulerImpl owner() {
        return owner;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (TaskImpl) obj;
        return this.id == that.id;
    }

    @Override
    public int hashCode() {
        return HashCommon.murmurHash3(id);
    }

    @Override
    public String toString() {
        return "TaskImpl[" +
                "id=" + id + ", " +
                "task=" + task + ", " +
                "executionType=" + executionType + ", " +
                "owner=" + owner + ']';
    }

}
