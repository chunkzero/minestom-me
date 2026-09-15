package net.minestom.server.event;

import net.minestom.server.ServerProcess;

import java.util.Objects;

/**
 * Object containing all the global event listeners.
 */
public final class GlobalEventHandler extends EventNodeImpl<Event> {
    private final ServerProcess process;

    public GlobalEventHandler(ServerProcess process) {
        super("global", EventFilter.ALL, null);
        this.process = Objects.requireNonNull(process);
    }

    @Override
    public ServerProcess process() {
        return process;
    }
}
