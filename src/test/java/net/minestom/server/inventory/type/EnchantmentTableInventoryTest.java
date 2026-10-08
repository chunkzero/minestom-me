package net.minestom.server.inventory.type;

import net.kyori.adventure.text.Component;
import net.minestom.server.ServerProcess;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.registry.RegistryKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnchantmentTableInventoryTest {
    private final ServerProcess process = ServerProcess.create();

    @AfterAll
    void closeProcess() {
        process.close();
    }

    @Test
    void displayedEnchantmentRoundTrips() {
        final var inventory = new EnchantmentTableInventory(process, Component.text("Enchant"));
        assertNull(inventory.getEnchantmentShown(EnchantmentTableInventory.EnchantmentSlot.MIDDLE));

        inventory.setEnchantmentShown(EnchantmentTableInventory.EnchantmentSlot.MIDDLE, Enchantment.SHARPNESS);
        assertEquals(Enchantment.SHARPNESS,
                inventory.getEnchantmentShown(EnchantmentTableInventory.EnchantmentSlot.MIDDLE));

        inventory.setEnchantmentShown(EnchantmentTableInventory.EnchantmentSlot.MIDDLE, null);
        assertNull(inventory.getEnchantmentShown(EnchantmentTableInventory.EnchantmentSlot.MIDDLE));
    }

    @Test
    void unknownEnchantmentIsRejected() {
        final var inventory = new EnchantmentTableInventory(process, Component.text("Enchant"));
        final RegistryKey<Enchantment> unknown = RegistryKey.of("minestom:unknown_enchantment");

        assertThrows(IllegalArgumentException.class, () ->
                inventory.setEnchantmentShown(EnchantmentTableInventory.EnchantmentSlot.MIDDLE, unknown));
    }
}
