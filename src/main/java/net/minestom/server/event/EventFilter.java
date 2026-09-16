package net.minestom.server.event;

import net.minestom.server.ServerProcess;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.trait.BlockEvent;
import net.minestom.server.event.trait.EntityEvent;
import net.minestom.server.event.trait.InstanceEvent;
import net.minestom.server.event.trait.InventoryEvent;
import net.minestom.server.event.trait.ItemEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.AbstractInventory;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Represents a filter for a specific {@link Event} type.
 * <p>
 * The handler represents a "target" of the event. This can be used
 * to create filters for all events of a specific type using information
 * about the target.
 * <p>
 * For example, the target of a {@link PlayerEvent} is a {@link Player} so
 * you could create a player event filter which checks if the target player
 * is in creative mode.
 *
 * @param <E> The event type to filter
 * @param <H> The handler type to filter on.
 */
public interface EventFilter<E extends Event, H> {

    EventFilter<Event, ?> ALL = from(Event.class, null, _ -> null);
    EventFilter<EntityEvent, Entity> ENTITY = from(EntityEvent.class, Entity.class, EntityEvent::getEntity);
    EventFilter<PlayerEvent, Player> PLAYER = from(PlayerEvent.class, Player.class, PlayerEvent::getPlayer);
    EventFilter<ItemEvent, ItemStack> ITEM = from(ItemEvent.class, ItemStack.class, ItemEvent::getItemStack);
    EventFilter<InstanceEvent, Instance> INSTANCE = from(InstanceEvent.class, Instance.class, InstanceEvent::getInstance);
    EventFilter<InventoryEvent, AbstractInventory> INVENTORY = from(InventoryEvent.class, AbstractInventory.class, InventoryEvent::getInventory);
    EventFilter<BlockEvent, Block> BLOCK = from(BlockEvent.class, Block.class, BlockEvent::getBlock);

    static <E extends Event, H> EventFilter<E, H> from(Class<E> eventType,
                                                       @Nullable Class<H> handlerType,
                                                       @Nullable Function<E, H> handlerGetter) {
        return from(eventType, handlerType, (_, event) -> handlerGetter != null ? handlerGetter.apply(event) : null);
    }

    /**
     * Gets the handler for the given event instance, or null if the event
     * type has no handler.
     *
     * @param process The dispatching process
     * @param event The event instance
     * @return The handler, if it exists for the given event
     */
    @Nullable H getHandler(ServerProcess process, E event);

    static <E extends Event, H> EventFilter<E, H> from(Class<E> eventType, @Nullable Class<H> handlerType,
                                                      BiFunction<ServerProcess, E, H> handlerGetter) {
        return new EventFilter<>() {
            @Override
            public @Nullable H getHandler(ServerProcess process, E event) {
                return handlerGetter.apply(process, event);
            }

            @Override
            public Class<E> eventType() {
                return eventType;
            }

            @Override
            public @Nullable Class<H> handlerType() {
                return handlerType;
            }
        };
    }

    @ApiStatus.Internal
    @SuppressWarnings("unchecked")
    default @Nullable H castHandler(ServerProcess process, Object event) {
        return getHandler(process, (E) event);
    }

    /**
     * The event type to filter on.
     *
     * @return The event type.
     */
    Class<E> eventType();

    /**
     * The type returned by {@link #getHandler(ServerProcess, Event)}.
     *
     * @return the handler type, null if not any
     */
    @Nullable Class<H> handlerType();
}
