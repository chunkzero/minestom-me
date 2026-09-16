package net.minestom.server.event.trait;

import net.minestom.server.entity.Player;

/**
 * Represents an {@link PlayerEvent} which happen in {@link Player#getInstance()}.
 * Useful if you need to listen to player events happening in its instance.
 * <p>
 * Requires a placed player. Connection and configuration events implement {@link PlayerEvent} directly.
 */
public interface PlayerInstanceEvent extends PlayerEvent, EntityInstanceEvent {
}
