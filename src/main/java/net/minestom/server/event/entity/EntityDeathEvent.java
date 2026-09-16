package net.minestom.server.event.entity;

import net.minestom.server.entity.Entity;
import net.minestom.server.event.trait.EntityEvent;

public class EntityDeathEvent implements EntityEvent {

    // TODO cause
    private final Entity entity;

    public EntityDeathEvent(Entity entity) {
        this.entity = entity;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }
}
