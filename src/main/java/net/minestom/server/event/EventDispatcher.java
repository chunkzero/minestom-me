package net.minestom.server.event;

import net.minestom.server.MinecraftServer;
import net.minestom.server.event.trait.CancellableEvent;

/**
 * Default-process event dispatch, scheduled for deletion.
 * Use the owning process's event handler for new event dispatch paths.
 */
public final class EventDispatcher {

    /** @deprecated Scheduled for deletion. Use the owning process event handler. */
    @SuppressWarnings("removal") // Default-process bridge pending ownership migration.
    @Deprecated(forRemoval = true)
    public static void call(Event event) {
        MinecraftServer.getGlobalEventHandler().call(event);
    }

    /** @deprecated Scheduled for deletion. Use the owning process event handler. */
    @SuppressWarnings("removal") // Default-process bridge pending ownership migration.
    @Deprecated(forRemoval = true)
    public static <E extends Event> ListenerHandle<E> getHandle(Class<E> handleType) {
        return MinecraftServer.getGlobalEventHandler().getHandle(handleType);
    }

    /** @deprecated Scheduled for deletion. Use the owning process event handler. */
    @SuppressWarnings("removal") // Default-process bridge pending ownership migration.
    @Deprecated(forRemoval = true)
    public static void callCancellable(CancellableEvent event, Runnable successCallback) {
        MinecraftServer.getGlobalEventHandler().callCancellable(event, successCallback);
    }
}
