package net.minestom.server.entity;

import net.minestom.server.ServerProcess;
import net.minestom.server.instance.EntityTracker;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns entity IDs and creates instance trackers for one process. */
public final class EntityManager {
    private final ServerProcess process;
    private final AtomicInteger lastEntityId = new AtomicInteger();

    public EntityManager(ServerProcess process) {
        this.process = Objects.requireNonNull(process);
    }

    public ServerProcess process() {
        return process;
    }

    /**
     * Allocates an ID shared by all instances in this process, including packet-only entities.
     * IDs from different processes may overlap; identify an entity by its process and ID together.
     */
    public int generateId() {
        return lastEntityId.incrementAndGet();
    }

    /** Creates a separate spatial tracker using this process's entity ownership. */
    public EntityTracker newTracker() {
        return EntityTracker.newTracker(process);
    }
}
