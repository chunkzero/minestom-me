package net.minestom.server.event;

import net.minestom.server.ProcessOwned;
import net.minestom.server.ServerProcess;

import java.util.Objects;

/**
 * Root event node owned by one server process. Dispatch supplies that process to all listeners.
 */
public final class ProcessEventHandler extends EventNodeImpl<Event> implements ProcessOwned {
    private final ServerProcess process;

    public ProcessEventHandler(ServerProcess process) {
        super("process", EventFilter.ALL, null);
        this.process = Objects.requireNonNull(process);
    }

    @Override
    public ServerProcess process() {
        return process;
    }
}
