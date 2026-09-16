package net.minestom.server.event.trait;

import net.minestom.server.entity.Entity;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an {@link EntityEvent} which happen in {@link Entity#getInstance()}.
 * Useful if you need to listen to entity events happening in its instance.
 * <p>
 * Events dispatched before entity placement have no instance.
 */
public interface EntityInstanceEvent extends EntityEvent, InstanceEvent {
    @Override
    default @Nullable Instance getInstance() {
        return getEntity().getInstance();
    }
}
