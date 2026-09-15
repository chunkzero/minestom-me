package net.minestom.server.recipe;

import net.minestom.server.ServerProcess;
import net.minestom.server.item.Material;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.DeclareRecipesPacket;
import net.minestom.server.recipe.display.RecipeDisplay;
import net.minestom.server.recipe.display.SlotDisplay;
import net.minestom.server.registry.TagKey;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@EnvTest
class RecipeTagsIntegrationTest {
    @Test
    void stonecutterTagsAndCacheFollowTheOwningRegistries(Env env) {
        var key = TagKey.<Material>ofHash("#test:stonecutting");
        var recipe = new Recipe() {
            @Override
            public RecipeBookCategory recipeBookCategory() {
                return RecipeBookCategory.CRAFTING_BUILDING_BLOCKS;
            }

            @Override
            public List<RecipeDisplay> createRecipeDisplays() {
                return List.of(new RecipeDisplay.Stonecutter(new SlotDisplay.Tag(key),
                        new SlotDisplay.Item(Material.STONE_SLAB), new SlotDisplay.Item(Material.STONECUTTER)));
            }
        };
        try (var second = ServerProcess.create()) {
            env.process().material().getOrCreateTag(key);
            second.material().getOrCreateTag(key);
            env.process().recipe().addRecipe(recipe);
            second.recipe().addRecipe(recipe);
            var firstPacket = packet(env.process());
            assertEquals(1, firstPacket.stonecutterRecipes().size());
            assertEquals(1, packet(second).stonecutterRecipes().size());

            second.material().removeTag(key);
            assertEquals(0, packet(second).stonecutterRecipes().size());
            assertSame(firstPacket, packet(env.process()));
        }
    }

    private static DeclareRecipesPacket packet(ServerProcess process) {
        return (DeclareRecipesPacket) SendablePacket.extractServerPacket(ConnectionState.PLAY,
                process.recipe().getDeclareRecipesPacket());
    }
}
