package net.minestom.server;

import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.Material;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.server.property.ServerProperties;
import net.minestom.server.recipe.Recipe;
import net.minestom.server.recipe.RecipeBookCategory;
import net.minestom.server.recipe.display.RecipeDisplay;
import net.minestom.server.recipe.display.SlotDisplay;
import net.minestom.server.registry.Registries;
import net.minestom.server.world.Difficulty;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerProcessIsolationTest {
    @Test
    void inventoryAndRecipeIdsAreAllocatedWithinEachProcess() {
        try (var pair = new ServerProcessPair()) {
            var first = new Inventory(pair.first(), InventoryType.CHEST_1_ROW, "First");
            new Inventory(pair.first(), InventoryType.CHEST_1_ROW, "Another");
            var second = new Inventory(pair.second(), InventoryType.CHEST_1_ROW, "Second");
            assertEquals(first.getWindowId(), second.getWindowId());
            var result = new SlotDisplay.Item(Material.STONE);
            var display = new RecipeDisplay.CraftingShapeless(List.of(result), result, new SlotDisplay.Item(Material.CRAFTING_TABLE));
            var recipe = new Recipe() {
                @Override
                public List<RecipeDisplay> createRecipeDisplays() {
                    return List.of(display);
                }

                @Override
                public RecipeBookCategory recipeBookCategory() {
                    return RecipeBookCategory.CRAFTING_BUILDING_BLOCKS;
                }
            };
            pair.first().recipeManager().addRecipe(recipe);
            pair.second().recipeManager().addRecipe(recipe);
            assertSame(display, pair.first().recipeManager().getRecipeDisplay(0, null));
            assertSame(display, pair.second().recipeManager().getRecipeDisplay(0, null));
            pair.first().close();
            assertSame(display, pair.second().recipeManager().getRecipeDisplay(0, null));
            assertEquals(second.getWindowId() + 1, new Inventory(pair.second(), InventoryType.CHEST_1_ROW, "Next").getWindowId());
        }
    }

    @Test
    void constructionOwnsIndependentManagers() throws IOException {
        try (var processes = new ServerProcessPair();
             var channel = SocketChannel.open()) {
            var first = processes.first();
            var second = processes.second();
            assertNotSame(first.registries(), second.registries());
            assertNotSame(first.commandManager(), second.commandManager());
            assertNotSame(first.eventHandler(), second.eventHandler());
            assertNotSame(first.schedulerManager(), second.schedulerManager());
            assertSame(first, first.connectionManager().process());
            assertSame(second, second.instanceManager().process());
            assertSame(second, second.server().process());

            var connection = new PlayerSocketConnection(second, channel,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Thread.currentThread(), Thread.currentThread());
            assertSame(second, connection.process());
        }
    }

    @Test
    void settingsBelongToTheirProcess() {
        try (var first = ServerProcess.create();
             var second = ServerProcess.create()) {
            first.setBrandName("First");
            first.setDifficulty(Difficulty.HARD);
            first.setCompressionThreshold(0);
            second.setBrandName("Second");
            second.setDifficulty(Difficulty.PEACEFUL);
            second.setCompressionThreshold(128);

            assertEquals("First", first.brandName());
            assertEquals(Difficulty.HARD, first.difficulty());
            assertEquals(0, first.compressionThreshold());
            first.setBrandName("Updated");
            assertEquals("Second", second.brandName());
            assertEquals(Difficulty.PEACEFUL, second.difficulty());
            assertEquals(128, second.compressionThreshold());

            try (var replacement = ServerProcess.create()) {
                assertEquals("Minestom", replacement.brandName());
                assertEquals(Difficulty.NORMAL, replacement.difficulty());
                assertEquals(256, replacement.compressionThreshold());
                assertEquals("Second", second.brandName());
            }
        }
    }

    @Test
    void closedProcessCannotStart() {
        var process = ServerProcess.create();
        process.close();
        assertDoesNotThrow(process::close);
        assertThrows(IllegalStateException.class, () ->
                process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)));
        assertFalse(process.isAlive());
        assertFalse(process.server().isOpen());
    }

    @Test
    void startingOneProcessDoesNotFreezeAnother() {
        var properties = ServerProperties.builder().freezeRegistriesOnStart(true).build();
        try (var first = ServerProcess.create(new Auth.Offline(), properties)) {
            first.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            assertTrue(first.registries().dimensionType().isFrozen());
            assertThrows(UnsupportedOperationException.class, () ->
                    first.registries().dimensionType().register("test:frozen", DimensionType.builder().build()));
            assertThrows(IllegalStateException.class, () -> first.setCompressionThreshold(64));

            try (var second = ServerProcess.create(new Auth.Offline(),
                    ServerProperties.builder().freezeRegistriesOnStart(false).build())) {
                var dimension = DimensionType.builder().ambientLight(0.5f).build();
                var key = second.registries().dimensionType().register("test:second", dimension);
                assertSame(dimension, second.registries().dimensionType().get(key));
                assertNull(first.registries().dimensionType().get(key));
                assertFalse(second.registries().dimensionType().isFrozen());
                second.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                assertFalse(second.registries().dimensionType().isFrozen());
                assertDoesNotThrow(() -> second.registries().dimensionType().register("test:after-start", dimension));
                assertTrue(first.registries().dimensionType().isFrozen());
                assertDoesNotThrow(() -> Registries.vanilla().dimensionType()
                        .register("test:standalone", dimension));
            }
        }
    }
}
