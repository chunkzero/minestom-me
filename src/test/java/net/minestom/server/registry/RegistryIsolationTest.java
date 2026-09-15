package net.minestom.server.registry;

import net.minestom.server.component.DataComponentMap;
import net.minestom.server.component.DataComponents;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.predicate.CollectionPredicate;
import net.minestom.server.instance.block.predicate.ComponentPredicateSet;
import net.minestom.server.instance.block.predicate.DataComponentPredicate;
import net.minestom.server.instance.block.predicate.DataComponentPredicates;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.predicate.ItemPredicate;
import net.minestom.server.utils.Range;
import net.minestom.server.world.biome.Biome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

class RegistryIsolationTest {
    @Test
    void staticEntriesAreSharedButTagDefinitionsAreIndependent() {
        var first = Registries.vanilla();
        var second = Registries.vanilla();
        var key = TagKey.<Block>ofHash("#minecraft:mineable/pickaxe");
        var firstTag = first.blocks().getTag(key);
        var secondTag = second.blocks().getTag(key);
        assertNotNull(firstTag);
        assertNotNull(secondTag);
        assertSame(Block.STONE, first.blocks().get(Block.STONE.key()));
        assertSame(first.blocks().get(Block.STONE.key()), second.blocks().get(Block.STONE.key()));
        var stoneKey = first.blocks().getKey(Block.STONE);
        assertTrue(firstTag.contains(first.blocks(), stoneKey));
        long secondRevision = Registries.tagsRevision(second);

        first.blocks().removeTag(key);
        first.blocks().getOrCreateTag(key);
        assertFalse(firstTag.contains(first.blocks(), stoneKey));
        assertTrue(secondTag.contains(second.blocks(), stoneKey));
        assertTrue(Block.staticRegistry().getTag(key).contains(Block.staticRegistry(), stoneKey));
        assertEquals(secondRevision, Registries.tagsRevision(second));

        assertTrue(first.blocks().removeTag(key));
        assertNotNull(second.blocks().getTag(key));
        var createdKey = TagKey.<Block>ofHash("#test:first");
        first.blocks().getOrCreateTag(createdKey);
        assertNull(second.blocks().getTag(createdKey));
    }

    @Test
    void dynamicTagMembershipAndRevisionsAreIndependent() {
        var first = Registries.vanilla();
        var second = Registries.vanilla();
        var tagKey = TagKey.<Biome>ofHash("#minecraft:is_overworld");
        var firstTag = first.biome().getTag(tagKey);
        var secondTag = second.biome().getTag(tagKey);
        assertNotNull(firstTag);
        assertNotNull(secondTag);
        var biomeKey = firstTag.resolve(first.biome()).iterator().next();
        long firstRevision = Registries.tagsRevision(first);
        long secondRevision = Registries.tagsRevision(second);

        assertTrue(first.biome().remove(biomeKey.key()));
        assertFalse(firstTag.contains(first.biome(), biomeKey));
        assertTrue(secondTag.contains(second.biome(), biomeKey));
        assertNotNull(second.biome().get(biomeKey));
        assertTrue(Registries.tagsRevision(first) > firstRevision);
        assertEquals(secondRevision, Registries.tagsRevision(second));
    }

    @Test
    void sharedMaterialPrototypesResolveEachRegistrysTags() {
        var first = Registries.vanilla();
        var second = Registries.vanilla();
        var repairable = Material.DIAMOND_SWORD.prototype().get(DataComponents.REPAIRABLE);
        assertNotNull(repairable);
        assertNotNull(repairable.key());
        assertTrue(repairable.contains(first.material(), Material.DIAMOND));
        assertTrue(first.material().removeTag(repairable.key()));
        assertFalse(repairable.contains(first.material(), Material.DIAMOND));
        assertTrue(repairable.contains(second.material(), Material.DIAMOND));

        var tool = Material.DIAMOND_PICKAXE.prototype().get(DataComponents.TOOL);
        assertNotNull(tool);
        float vanillaSpeed = tool.getSpeed(second.blocks(), Block.STONE);
        assertTrue(vanillaSpeed > tool.defaultMiningSpeed());
        first.blocks().removeTag(TagKey.ofHash("#minecraft:mineable/pickaxe"));
        assertEquals(tool.defaultMiningSpeed(), tool.getSpeed(first.blocks(), Block.STONE));
        assertEquals(vanillaSpeed, tool.getSpeed(second.blocks(), Block.STONE));
    }

    @Test
    void nestedItemPredicatesKeepTheOwningRegistries() {
        var first = Registries.vanilla();
        var second = Registries.vanilla();
        var materials = Material.DIAMOND_SWORD.prototype().get(DataComponents.REPAIRABLE);
        assertNotNull(materials);
        var itemPredicate = new ItemPredicate(materials, null, null);
        var bundlePredicate = new DataComponentPredicate.BundleContents(
                CollectionPredicate.<ItemStack, ItemPredicate>builder()
                        .mustContain(itemPredicate)
                        .mustMatchCount(itemPredicate, new Range.Int(1))
                        .build());
        var predicates = new DataComponentPredicates(DataComponentMap.EMPTY,
                new ComponentPredicateSet(List.of(bundlePredicate)));
        var bundle = ItemStack.of(Material.BUNDLE).with(DataComponents.BUNDLE_CONTENTS,
                List.of(ItemStack.of(Material.DIAMOND)));
        assertTrue(predicates.test(first, bundle));
        assertNotNull(materials.key());
        first.material().removeTag(materials.key());
        assertFalse(predicates.test(first, bundle));
        assertTrue(predicates.test(second, bundle));
    }
}
