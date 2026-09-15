package net.minestom.server.listener;

import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerGameRulesRequestEvent;
import net.minestom.server.event.player.PlayerSetGameRulesEvent;
import net.minestom.server.network.packet.client.play.ClientSetGameRulesPacket;
import net.minestom.server.network.packet.client.play.ClientStatusPacket;

public final class PlayerSettingsMenuListener {

    @SuppressWarnings("removal") // Default-process bridge pending ownership migration.
    public static void requestGameRules(ClientStatusPacket ignored, Player player) {
        EventDispatcher.call(new PlayerGameRulesRequestEvent(player));
    }

    @SuppressWarnings("removal") // Default-process bridge pending ownership migration.
    public static void setGameRules(ClientSetGameRulesPacket packet, Player player) {
        EventDispatcher.call(new PlayerSetGameRulesEvent(player, packet.entries()));
    }

    //todo: add listeners for setting difficulty
}
