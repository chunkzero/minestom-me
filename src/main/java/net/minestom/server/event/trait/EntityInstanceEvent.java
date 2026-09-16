package net.minestom.server.event.trait;

import net.minestom.server.entity.Entity;
import net.minestom.server.instance.Instance;

import java.util.Objects;

/**
 * Represents an {@link EntityEvent} which happen in {@link Entity#getInstance()}.
 * Useful if you need to listen to entity events happening in its instance.
 * <p>
 * Requires a placed entity. Events that can fire before placement, such as velocity,
 * equipment, and potion changes, implement {@link EntityEvent} directly.
 */
public interface EntityInstanceEvent extends EntityEvent, InstanceEvent {
    @Override
    default Instance getInstance() {
        return Objects.requireNonNull(getEntity().getInstance(), "Entity instance events require a placed entity");
    }
}
