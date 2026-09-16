package net.minestom.server.event.trait;

import net.minestom.server.entity.Player;

/**
 * Represents an {@link PlayerEvent} which happen in {@link Player#getInstance()}.
 * Useful if you need to listen to player events happening in its instance.
 * <p>
 * Events dispatched before player placement have no instance.
 */
public interface PlayerInstanceEvent extends PlayerEvent, EntityInstanceEvent {
}
