

package net.minestom.server.snapshot;

import net.minestom.server.ServerProcess;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.UnknownNullability;

import java.util.Collection;

/**
 * Represents the complete state of the server at a given moment.
 */
public sealed interface ServerSnapshot extends Snapshot
        permits SnapshotImpl.Server {
    Collection<InstanceSnapshot> instances();

    Collection<EntitySnapshot> entities();

    @UnknownNullability EntitySnapshot entity(int id);

    /** Builds a snapshot of the supplied process at a safe point, when its state is stable. */
    @ApiStatus.Experimental
    static ServerSnapshot update(ServerProcess process) {
        return SnapshotUpdater.update(process);
    }
}
