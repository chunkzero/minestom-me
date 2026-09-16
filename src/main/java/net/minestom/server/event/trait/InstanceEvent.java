package net.minestom.server.event.trait;

import net.minestom.server.event.Event;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.Nullable;

/**
 * Represents any event targeting an {@link Instance}.
 */
public interface InstanceEvent extends Event {

    /**
     * Gets the instance.
     *
     * @return instance, or null for an entity event before placement
     */
    @Nullable Instance getInstance();
}
