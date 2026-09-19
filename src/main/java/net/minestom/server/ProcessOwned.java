package net.minestom.server;

/**
 * An object owned by one {@link ServerProcess} for its whole lifetime.
 * <p>
 * Ownership never changes: {@link #process()} returns the same process before and after the owner
 * or the process closes. It does not promise that the process is still running; check
 * {@link ServerProcess#isAlive()} when that matters. Callers reach services through the process.
 */
public interface ProcessOwned {
    /** The process that owns this object's lifetime and services. */
    ServerProcess process();
}
