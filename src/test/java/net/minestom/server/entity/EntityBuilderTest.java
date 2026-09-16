package net.minestom.server.entity;

import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ai.goal.RandomLookAroundGoal;
import net.minestom.server.entity.metadata.monster.zombie.ZombieMeta;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.entity.EntitySpawnEvent;
import net.minestom.server.event.entity.EntityVelocityEvent;
import net.minestom.server.event.instance.AddEntityToInstanceEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.ServerProcessPair;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class EntityBuilderTest {
    @Test
    void listenersObserveSettingsInitializationAndSpawnInOrder() {
        var calls = new ArrayList<String>();
        var owners = new ArrayList<ServerProcess>();
        var builder = Entity.builder(EntityType.ZOMBIE)
                .noGravity(true)
                .autoViewable(false)
                .velocity(new Vec(1, 0, 0))
                .velocity(new Vec(2, 0, 0))
                .initialize((process, entity) -> {
                    assertSame(process, entity.process());
                    assertNull(entity.getInstance());
                    assertNull(entity.acquirable().assignedThread());
                    assertTrue(entity.hasNoGravity());
                    assertEquals(new Vec(3, 0, 0), entity.getVelocity());
                    calls.add("initialize");
                    entity.setVelocity(new Vec(4, 0, 0));
                })
                .initialize(_ -> calls.add("initialize again"))
                .addListener(EntityVelocityEvent.class, (process, event) -> {
                    owners.add(process);
                    calls.add("velocity");
                    event.setVelocity(event.getVelocity().add(1, 0, 0));
                })
                .addListener(EntitySpawnEvent.class, _ -> calls.add("spawn"));
        assertTrue(calls.isEmpty());

        try (var process = ServerProcess.create()) {
            var errors = new ArrayList<Throwable>();
            process.exception().setExceptionHandler(errors::add);
            var instance = process.instance().createInstanceContainer(ChunkLoader.noop());
            var position = new Pos(1, 2, 3, 45, 10);
            CompletableFuture<? extends Entity> spawned = builder.spawn(instance, position);
            var entity = spawned.join();
            assertEquals(List.of("velocity", "initialize", "velocity", "initialize again", "spawn"), calls);
            assertEquals(List.of(process, process), owners);
            assertEquals(position, entity.getPosition());
            assertEquals(new Vec(5, 0, 0), entity.getVelocity());
            assertSame(entity, instance.getEntityById(entity.getEntityId()));

            entity.setNoGravity(false);
            assertFalse(entity.hasNoGravity());
            entity.remove();
            assertTrue(instance.getEntities().isEmpty());
            assertTrue(errors.isEmpty(), errors::toString);
        }
    }

    @Test
    void reusedBuilderCreatesTypedEntitiesAndIndependentListenerRegistrations() {
        var initialized = new ArrayList<TestCreature>();
        var firstSpawns = new ArrayList<ServerProcess>();
        var allSpawns = new ArrayList<Entity>();
        var builder = Entity.builder(EntityType.ZOMBIE, TestCreature::new)
                .noGravity(true)
                .autoViewable(false)
                .initialize((process, creature) -> {
                    assertSame(process.registries(), creature.process().registries());
                    creature.addAIGroup(List.of(new RandomLookAroundGoal(creature, 20)), List.of());
                    creature.editEntityMeta(ZombieMeta.class, meta -> meta.setBaby(true));
                    initialized.add(creature);
                })
                .addListener(EventListener.builder(EntitySpawnEvent.class)
                        .expireCount(1)
                        .handler((process, _) -> firstSpawns.add(process))
                        .build())
                .addListener(EntitySpawnEvent.class, event -> allSpawns.add(event.getEntity()));
        assertTrue(initialized.isEmpty());

        try (var pair = new ServerProcessPair()) {
            var errors = new ArrayList<Throwable>();
            pair.first().exception().setExceptionHandler(errors::add);
            pair.second().exception().setExceptionHandler(errors::add);
            var firstInstance = pair.first().instance().createInstanceContainer(ChunkLoader.noop());
            var secondInstance = pair.second().instance().createInstanceContainer(ChunkLoader.noop());
            var otherFirstInstance = pair.first().instance().createInstanceContainer(ChunkLoader.noop());
            CompletableFuture<TestCreature> firstSpawn = builder.spawn(firstInstance);
            var first = firstSpawn.join();
            assertEquals(List.of(pair.first()), firstSpawns);
            first.setInstance(otherFirstInstance).join();
            var second = builder.spawn(secondInstance).join();

            assertEquals(List.of(first, second), initialized);
            assertEquals(List.of(pair.first(), pair.second()), firstSpawns);
            assertEquals(List.of(first, first, second), allSpawns);
            assertSame(pair.first(), first.process());
            assertSame(pair.second(), second.process());
            assertEquals(first.getEntityId(), second.getEntityId());
            assertNotEquals(first.getUuid(), second.getUuid());
            assertNotSame(first.getAIGroups().iterator().next(), second.getAIGroups().iterator().next());
            first.editEntityMeta(ZombieMeta.class, meta -> meta.setBaby(false));
            assertTrue(((ZombieMeta) second.getEntityMeta()).isBaby());
            assertThrows(IllegalArgumentException.class, () -> first.setInstance(secondInstance));
            assertSame(otherFirstInstance, first.getInstance());
            assertTrue(errors.isEmpty(), errors::toString);

            var foreignFactory = Entity.builder(_ -> first).spawn(secondInstance);
            assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class, foreignFactory::join).getCause());
            assertSame(otherFirstInstance, first.getInstance());
            assertFalse(first.isRemoved());
        }
    }

    @Test
    void futureWaitsForChunkLoadingAndSpawnListeners() {
        try (var process = ServerProcess.create()) {
            var instance = new LoadingInstance(process);
            process.instance().registerInstance(instance);
            var created = new AtomicReference<Entity>();
            var spawnCalls = new AtomicInteger();
            var spawned = Entity.builder(EntityType.ZOMBIE)
                    .autoViewable(false)
                    .initialize(created::set)
                    .addListener(EntitySpawnEvent.class, _ -> spawnCalls.incrementAndGet())
                    .spawn(instance);

            assertNotNull(created.get());
            assertFalse(spawned.isDone());
            assertEquals(0, spawnCalls.get());
            assertTrue(instance.getEntities().isEmpty());
            instance.loading.complete(new DynamicChunk(instance, 0, 0));
            assertSame(created.get(), spawned.join());
            assertEquals(1, spawnCalls.get());
            assertSame(created.get(), instance.getEntityById(created.get().getEntityId()));
            created.get().remove();
        }
    }

    @Test
    void cancelledPlacementRemovesEntityWithoutSpawning() {
        try (var process = ServerProcess.create()) {
            var instance = process.instance().createInstanceContainer(ChunkLoader.noop());
            var created = new AtomicReference<Entity>();
            var spawnCalls = new AtomicInteger();
            var spawned = Entity.builder(EntityType.ZOMBIE)
                    .initialize(created::set)
                    .addListener(AddEntityToInstanceEvent.class, event -> event.setCancelled(true))
                    .addListener(EntitySpawnEvent.class, _ -> spawnCalls.incrementAndGet())
                    .spawn(instance);

            assertInstanceOf(CancellationException.class, assertThrows(CompletionException.class, spawned::join).getCause());
            assertEquals(0, spawnCalls.get());
            assertTrue(created.get().isRemoved());
            assertNull(created.get().getInstance());
            assertTrue(instance.getEntities().isEmpty());
            assertTrue(instance.getChunks().isEmpty());
        }
    }

    @Test
    void initializationAndPlacementFailuresDoNotReturnLiveEntities() {
        try (var pair = new ServerProcessPair()) {
            var firstErrors = new ArrayList<Throwable>();
            var secondErrors = new ArrayList<Throwable>();
            pair.first().exception().setExceptionHandler(firstErrors::add);
            pair.second().exception().setExceptionHandler(secondErrors::add);
            var instance = pair.second().instance().createInstanceContainer(ChunkLoader.noop());
            var failure = new IllegalStateException("initialization failed");
            var created = new AtomicReference<Entity>();
            var initialization = Entity.builder(EntityType.ZOMBIE)
                    .initialize(entity -> {
                        created.set(entity);
                        throw failure;
                    })
                    .spawn(instance);
            assertSame(failure, assertThrows(CompletionException.class, initialization::join).getCause());
            assertTrue(created.get().isRemoved());
            assertTrue(instance.getChunks().isEmpty());

            var spawnFailure = new IllegalStateException("spawn failed");
            var spawnCalls = new AtomicInteger();
            var placement = Entity.builder(process -> new Entity(process, EntityType.ZOMBIE) {
                        @Override
                        public void spawn() {
                            throw spawnFailure;
                        }
                    })
                    .initialize(created::set)
                    .addListener(EntitySpawnEvent.class, _ -> spawnCalls.incrementAndGet())
                    .spawn(instance);
            assertSame(spawnFailure, assertThrows(CompletionException.class, placement::join).getCause());
            assertEquals(0, spawnCalls.get());
            assertTrue(created.get().isRemoved());
            assertNull(created.get().getInstance());
            assertTrue(instance.getEntities().isEmpty());
            assertTrue(firstErrors.isEmpty());
            assertEquals(List.of(failure, spawnFailure), secondErrors);

            var loadingInstance = new LoadingInstance(pair.second());
            pair.second().instance().registerInstance(loadingInstance);
            var loading = Entity.builder(EntityType.ZOMBIE).initialize(created::set).spawn(loadingInstance);
            loadingInstance.loading.completeExceptionally(failure);
            assertSame(failure, assertThrows(CompletionException.class, loading::join).getCause());
            assertTrue(created.get().isRemoved());
            assertNull(created.get().getInstance());
            assertTrue(loadingInstance.getEntities().isEmpty());
        }
    }

    @Test
    void subtypeAndCustomBuildersPreserveTheirFluentTypes() {
        var itemStack = ItemStack.of(Material.STONE);
        var itemBuilder = ItemEntity.builder(itemStack)
                .initialize(item -> assertEquals(500, item.getPickupDelay()))
                .noGravity(true)
                .pickupDelay(Duration.ofMillis(500))
                .autoViewable(false)
                .mergeable(false);
        var customBuilder = TestCreature.builder(EntityType.ZOMBIE)
                .noGravity(true)
                .autoViewable(false)
                .initialize(creature -> assertEquals(0, creature.getRemovalAnimationDelay()))
                .removalDelay(0);
        assertSame(customBuilder, customBuilder.noGravity(true));

        try (var process = ServerProcess.create()) {
            var instance = process.instance().createInstanceContainer(ChunkLoader.noop());
            CompletableFuture<ItemEntity> itemSpawn = itemBuilder.spawn(instance);
            var item = itemSpawn.join();
            assertEquals(itemStack, item.getItemStack());
            assertFalse(item.isMergeable());
            assertTrue(item.hasNoGravity());

            CompletableFuture<TestCreature> customSpawn = customBuilder.spawn(instance);
            assertTrue(customSpawn.join().hasNoGravity());
            var living = LivingEntity.builder(EntityType.ZOMBIE)
                    .autoViewable(false).initialize(entity -> entity.setHealth(10)).spawn(instance).join();
            assertEquals(10, living.getHealth());
            var creature = EntityCreature.builder(EntityType.ZOMBIE).autoViewable(false).spawn(instance).join();
            assertSame(creature, creature.getNavigator().getEntity());
            var projectile = EntityProjectile.builder(creature, EntityType.ARROW).autoViewable(false).spawn(instance).join();
            assertSame(creature, projectile.getShooter());
            var orb = ExperienceOrb.builder((short) 5).autoViewable(false).spawn(instance).join();
            assertEquals(5, orb.getExperienceCount());
        }
    }

    static final class TestCreature extends EntityCreature {
        TestCreature(ServerProcess process, EntityType type) {
            super(process, type);
        }

        public static TestCreatureBuilder builder(EntityType type) {
            return new TestCreatureBuilder(type);
        }
    }

    static final class TestCreatureBuilder extends EntityBuilder<TestCreature, TestCreatureBuilder> {
        TestCreatureBuilder(EntityType type) {
            super(process -> new TestCreature(process, type));
        }

        @Override
        protected TestCreatureBuilder self() {
            return this;
        }

        TestCreatureBuilder removalDelay(int delay) {
            return configure(creature -> creature.setRemovalAnimationDelay(delay));
        }
    }

    private static final class LoadingInstance extends InstanceContainer {
        private final CompletableFuture<@Nullable Chunk> loading = new CompletableFuture<>();

        LoadingInstance(ServerProcess process) {
            super(process, UUID.randomUUID(), DimensionType.OVERWORLD);
        }

        @Override
        public CompletableFuture<@Nullable Chunk> loadOptionalChunk(int chunkX, int chunkZ) {
            return loading;
        }
    }
}
