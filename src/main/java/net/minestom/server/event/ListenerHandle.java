package net.minestom.server.event;

import net.minestom.server.ServerProcess;

/**
 * Represents a key to a listenable event, retrievable from {@link EventNode#getHandle(Class)}.
 * Useful to avoid map lookups.
 * <p>
 * A handle belongs to its node. Do not share owned handles between processes.
 *
 * @param <E> the event type
 */
public sealed interface ListenerHandle<E extends Event> permits EventNodeImpl.Handle {
    /**
     * Calls the given event using the process currently owning this handle's node.
     * Handles retain their node and follow its ownership when it is detached or reattached.
     * Standalone nodes require {@link #call(ServerProcess, Event)} instead.
     * Will try to fast exit the execution when possible if {@link #hasListener()} return {@code false}.
     * <p>
     * Anonymous and subclasses are not supported, events must have the exact type {@code E}.
     *
     * @param event the event to call
     * @throws IllegalStateException if the node is not attached to a process root
     */
    void call(E event);

    /** Dispatches with explicit context, required for a standalone node. */
    void call(ServerProcess process, E event);

    /**
     * Gets if any listener has been registered for the given handle.
     * May trigger an update if the cached data is not correct.
     * <p>
     * Useful if you are able to avoid expensive computation in the case where
     * the event is unused. Be aware that {@link #call(Event)}
     * has similar optimization built-in.
     *
     * @return true if the event has 1 or more listeners
     */
    boolean hasListener();
}
