package net.minestom.server.event.entity;

import net.minestom.server.entity.Entity;
import net.minestom.server.event.trait.EntityEvent;
import net.minestom.server.potion.Potion;

public class EntityPotionRemoveEvent implements EntityEvent {

    private final Entity entity;
    private final Potion potion;

    public EntityPotionRemoveEvent(Entity entity, Potion potion) {
        this.entity = entity;
        this.potion = potion;
    }

    /**
     * Returns the potion that was removed.
     *
     * @return the removed potion.
     */
    public Potion getPotion() {
        return potion;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
