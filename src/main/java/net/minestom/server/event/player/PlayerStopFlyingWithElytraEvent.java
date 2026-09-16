package net.minestom.server.event.player;

import net.minestom.server.entity.Player;
import net.minestom.server.event.trait.PlayerEvent;

public class PlayerStopFlyingWithElytraEvent implements PlayerEvent {

    private final Player player;

    public PlayerStopFlyingWithElytraEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
