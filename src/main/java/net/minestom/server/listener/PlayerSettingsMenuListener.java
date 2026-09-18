package net.minestom.server.listener;

import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerGameRulesRequestEvent;
import net.minestom.server.event.player.PlayerSetGameRulesEvent;
import net.minestom.server.network.packet.client.play.ClientSetGameRulesPacket;
import net.minestom.server.network.packet.client.play.ClientStatusPacket;

public final class PlayerSettingsMenuListener {

    public static void requestGameRules(ClientStatusPacket ignored, Player player) {
        player.process().eventHandler().call(new PlayerGameRulesRequestEvent(player));
    }

    public static void setGameRules(ClientSetGameRulesPacket packet, Player player) {
        player.process().eventHandler().call(new PlayerSetGameRulesEvent(player, packet.entries()));
    }

    //todo: add listeners for setting difficulty
}
