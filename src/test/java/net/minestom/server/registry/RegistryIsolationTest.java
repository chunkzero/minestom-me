package net.minestom.server.registry;

import net.minestom.server.component.DataComponents;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import net.minestom.server.world.biome.Biome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryIsolationTest {
    @Test
    void staticEntriesAreSharedButTagDefinitionsAreIndependent() {
        var first = Registries.vanilla();
        var second = Registries.vanilla();
        var key = TagKey.<Block>ofHash("#minecraft:mineable/pickaxe");
        var firstTag = (RegistryTagImpl.Backed<Block>) first.blocks().getTag(key);
        var secondTag = second.blocks().getTag(key);
        assertNotNull(firstTag);
        assertNotNull(secondTag);
        assertNotSame(firstTag, secondTag);
        assertSame(Block.STONE, first.blocks().get(Block.STONE.key()));
        assertSame(first.blocks().get(Block.STONE.key()), second.blocks().get(Block.STONE.key()));
        var stoneKey = first.blocks().getKey(Block.STONE);
        assertTrue(firstTag.contains(stoneKey));
        long secondRevision = Registries.tagsRevision(second);

        firstTag.remove(stoneKey);
        assertFalse(firstTag.contains(stoneKey));
        assertTrue(secondTag.contains(stoneKey));
        assertTrue(Block.staticRegistry().getTag(key).contains(stoneKey));
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
        var biomeKey = firstTag.iterator().next();
        long firstRevision = Registries.tagsRevision(first);
        long secondRevision = Registries.tagsRevision(second);

        assertTrue(first.biome().remove(biomeKey.key()));
        assertFalse(firstTag.contains(biomeKey));
        assertTrue(secondTag.contains(biomeKey));
        assertNotNull(second.biome().get(biomeKey));
        assertTrue(Registries.tagsRevision(first) > firstRevision);
        assertEquals(secondRevision, Registries.tagsRevision(second));
    }

    @Test
    void sharedMaterialPrototypesDoNotCaptureAProcessesTags() {
        var registries = Registries.vanilla();
        var repairable = Material.DIAMOND_SWORD.prototype().get(DataComponents.REPAIRABLE);
        assertNotNull(repairable);
        assertNotNull(repairable.key());
        var localTag = (RegistryTagImpl.Backed<Material>) registries.material().getTag(repairable.key());
        assertNotNull(localTag);
        var material = repairable.iterator().next();
        localTag.remove(material);
        assertFalse(localTag.contains(material));
        assertTrue(repairable.contains(material));
    }
}
