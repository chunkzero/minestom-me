package net.minestom.server.instance.block.predicate;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.SuspiciousGravelBlockHandler;
import net.minestom.server.registry.Registries;
import net.minestom.testing.RegistriesTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RegistriesTest
public class BlockPredicateRegistriesTest {

    // See sibling files for blocks and properties tests

    @Nested
    class NbtPredicate {
        @Test
        public void testMatching(Registries registries) {
            var predicate = new BlockPredicate(CompoundBinaryTag.builder()
                    .putString("LootTable", "minecraft:test")
                    .build());
            var block = Block.SUSPICIOUS_GRAVEL
                    .withHandler(SuspiciousGravelBlockHandler.INSTANCE)
                    .withNbt(CompoundBinaryTag.builder()
                    .putString("LootTable", "minecraft:test")
                    .build());
            assertTrue(predicate.test(registries, block));
        }

        @Test
        public void testEmptyTarget(Registries registries) {
            var predicate = new BlockPredicate(CompoundBinaryTag.builder()
                    .putString("LootTable", "minecraft:test")
                    .build());
            var block = Block.SUSPICIOUS_GRAVEL
                    .withHandler(SuspiciousGravelBlockHandler.INSTANCE)
                    .withNbt(CompoundBinaryTag.builder()
                    .build());
            assertFalse(predicate.test(registries, block));
        }

        @Test
        public void testNoBlockEntity(Registries registries) {
            // Never match if the block has no client block entity

            var predicate = new BlockPredicate(CompoundBinaryTag.builder().build());
            var block = Block.STONE;
            assertFalse(predicate.test(registries, block), "stone should not match empty");
        }

        @Test
        public void testNoExposedTags(Registries registries) {
            var predicate = new BlockPredicate(CompoundBinaryTag.builder().putString("LootTable", "minecraft:stone").build());
            // No exposed tags because no block handler so cannot match
            assertFalse(predicate.test(registries, Block.SUSPICIOUS_GRAVEL.withHandler(SuspiciousGravelBlockHandler.INSTANCE_NO_TAGS)
                    .withNbt(CompoundBinaryTag.builder().putString("LootTable", "minecraft:stone").build())));

            // In this case its fine because when there is no block handler we send the entire block entity
            assertTrue(predicate.test(registries, Block.SUSPICIOUS_GRAVEL.withNbt(CompoundBinaryTag.builder().putString("LootTable", "minecraft:stone").build())));
        }
    }


    // Combinations

    @Test
    public void emptyMatchAnything(Registries registries) {
        var predicate = new BlockPredicate(null, null, null);
        assertTrue(predicate.test(registries, Block.STONE_STAIRS));
        assertTrue(predicate.test(registries, Block.STONE_STAIRS.withProperty("facing", "east")));
        assertTrue(predicate.test(registries, Block.SUSPICIOUS_GRAVEL.withHandler(SuspiciousGravelBlockHandler.INSTANCE)));
        assertTrue(predicate.test(registries, Block.SUSPICIOUS_GRAVEL.withNbt(CompoundBinaryTag.builder().build())));
        assertTrue(predicate.test(registries, Block.SUSPICIOUS_GRAVEL.withNbt(CompoundBinaryTag.builder().putString("LootTable", "minecraft:test").build())));
        assertTrue(predicate.test(registries, Block.SUSPICIOUS_GRAVEL.withHandler(SuspiciousGravelBlockHandler.INSTANCE)
                .withNbt(CompoundBinaryTag.builder().putString("LootTable", "minecraft:test").build())));
    }

    @Test
    public void blockAlone(Registries registries) {
        var predicate = new BlockPredicate(Block.STONE);
        assertTrue(predicate.test(registries, Block.STONE));
        assertFalse(predicate.test(registries, Block.DIRT));
    }

    @Test
    public void propsAlone(Registries registries) {
        var predicate = new BlockPredicate(PropertiesPredicate.exact("facing", "east"));
        assertTrue(predicate.test(registries, Block.STONE_STAIRS.withProperty("facing", "east")));
        assertTrue(predicate.test(registries, Block.FURNACE.withProperty("facing", "east")));
        assertFalse(predicate.test(registries, Block.FURNACE));
    }

    @Test
    public void nbtAlone(Registries registries) {
        var predicate = new BlockPredicate(CompoundBinaryTag.builder().putString("LootTable", "minecraft:stone").build());
        assertTrue(predicate.test(registries, Block.SUSPICIOUS_GRAVEL.withHandler(SuspiciousGravelBlockHandler.INSTANCE)
                .withNbt(CompoundBinaryTag.builder().putString("LootTable", "minecraft:stone").build())));
    }
}
