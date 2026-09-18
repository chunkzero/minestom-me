package net.minestom.server.listener;

import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerAnvilInputEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.network.packet.client.play.ClientNameItemPacket;

public final class AnvilListener {

    public static void nameItemListener(ClientNameItemPacket packet, Player player) {
        if (!(player.getOpenInventory() instanceof Inventory openInventory))
            return;
        if (openInventory.getInventoryType() != InventoryType.ANVIL)
            return;

        player.process().eventHandler().call(new PlayerAnvilInputEvent(player, openInventory, packet.itemName()));
    }

    private AnvilListener() {
    }

}
