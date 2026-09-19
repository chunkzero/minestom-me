package net.minestom.server.instance;

import net.minestom.server.world.biome.Biome;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class ChunkDefaultBiomeIntegrationTest {

    @Test
    void defaultsAndResetUseOwningRegistry() {
        try (var pair = new ServerProcessPair()) {
            var biomes = pair.second().registries().biome();
            var plains = biomes.get(Biome.PLAINS);
            assertTrue(biomes.remove(Biome.PLAINS.key()));
            biomes.register(Biome.PLAINS.key(), plains);
            assertNotEquals(pair.first().registries().biome().getId(Biome.PLAINS), biomes.getId(Biome.PLAINS));

            var first = pair.first().instanceManager().createInstanceContainer(ChunkLoader.noop());
            var second = pair.second().instanceManager().createInstanceContainer(ChunkLoader.noop());
            first.loadChunk(0, 0).join();
            var chunk = second.loadChunk(0, 0).join();
            assertEquals(Biome.PLAINS, second.getBiome(0, 0, 0));
            second.setBiome(0, 0, 0, Biome.BADLANDS);
            chunk.lockWriteLock();
            try {
                chunk.reset();
            } finally {
                chunk.unlockWriteLock();
            }
            assertEquals(Biome.PLAINS, first.getBiome(0, 0, 0));
            assertEquals(Biome.PLAINS, second.getBiome(0, 0, 0));
            assertEquals(Biome.PLAINS, second.getBiome(15, chunk.getMaxSection() * 16 - 1, 15));
        }
    }

    @Test
    public void newChunkDefaultsToPlains(Env env) {
        var instance = env.createEmptyInstance();
        var chunk = instance.loadChunk(0, 0).join();

        assertEquals(Biome.PLAINS, instance.getBiome(0, 0, 0));
        assertEquals(Biome.PLAINS, instance.getBiome(15, chunk.getMaxSection() * 16 - 1, 15));
    }

    @Test
    public void resetRestoresPlains(Env env) {
        var instance = env.createEmptyInstance();
        var chunk = instance.loadChunk(0, 0).join();

        instance.setBiome(0, 0, 0, Biome.BADLANDS);
        assertEquals(Biome.BADLANDS, instance.getBiome(0, 0, 0));

        chunk.lockWriteLock();
        try {
            chunk.reset();
        } finally {
            chunk.unlockWriteLock();
        }
        assertEquals(Biome.PLAINS, instance.getBiome(0, 0, 0));
    }
}
