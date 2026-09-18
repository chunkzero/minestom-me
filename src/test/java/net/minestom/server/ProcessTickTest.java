package net.minestom.server;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityTickEvent;
import net.minestom.server.event.instance.AddEntityToInstanceEvent;
import net.minestom.server.event.instance.InstanceTickEvent;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.SharedInstance;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.property.ServerProperties;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class ProcessTickTest {
    @Test
    void entityIdsAreSharedAcrossInstancesAndIndependentAcrossProcesses() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var firstEntity = entity(first);
            var secondEntity = entity(second);
            assertEquals(firstEntity.getEntityId(), secondEntity.getEntityId());
            int packetOnlyId = first.generateEntityId();
            var otherEntity = entity(first);
            assertNotEquals(firstEntity.getEntityId(), packetOnlyId);
            assertNotEquals(packetOnlyId, otherEntity.getEntityId());
            assertNotEquals(firstEntity.getEntityId(), otherEntity.getEntityId());

            var firstInstance = first.instance().createInstanceContainer(ChunkLoader.noop());
            var otherInstance = first.instance().createInstanceContainer(ChunkLoader.noop());
            var secondInstance = second.instance().createInstanceContainer(ChunkLoader.noop());
            firstEntity.setInstance(firstInstance).join();
            otherEntity.setInstance(otherInstance).join();
            secondEntity.setInstance(secondInstance).join();
            assertSame(firstEntity, firstInstance.getEntityById(firstEntity.getEntityId()));
            assertSame(secondEntity, secondInstance.getEntityById(firstEntity.getEntityId()));
            firstEntity.setInstance(otherInstance).join();
            assertNull(firstInstance.getEntityById(firstEntity.getEntityId()));
            assertSame(firstEntity, otherInstance.getEntityById(firstEntity.getEntityId()));
            assertSame(otherEntity, otherInstance.getEntityById(otherEntity.getEntityId()));
        }
    }

    @Test
    void closingWhileSchedulerWaitsToEnterTickDoesNotReportAnError() throws InterruptedException {
        try (var process = ServerProcess.create()) {
            var errors = new CopyOnWriteArrayList<Throwable>();
            process.exception().setExceptionHandler(errors::add);
            Thread closer;
            synchronized (process.ticker()) {
                process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                Thread scheduler = null;
                while (scheduler == null && System.nanoTime() < deadline) {
                    scheduler = Thread.getAllStackTraces().keySet().stream()
                            .filter(thread -> thread.getName().equals("Ms-TickScheduler-" + process.id()))
                            .findFirst().orElse(null);
                    if (scheduler == null) Thread.sleep(1);
                }
                assertNotNull(scheduler);
                while (scheduler.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.BLOCKED, scheduler.getState());
                closer = Thread.startVirtualThread(process::close);
                while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(1);
                assertFalse(process.isAlive());
            }
            closer.join(5000);
            assertFalse(closer.isAlive());
            assertTrue(errors.isEmpty(), errors::toString);
            assertThrows(IllegalStateException.class, () -> process.ticker().tick(System.nanoTime()));
        }
    }

    @Test
    void instancesEntitiesAndTickEventsUseTheirOwner() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var errors = new CopyOnWriteArrayList<Throwable>();
            first.exception().setExceptionHandler(errors::add);
            second.exception().setExceptionHandler(errors::add);
            var firstInstance = first.instance().createInstanceContainer(ChunkLoader.noop());
            var dimension = second.registries().dimensionType().register("test:second", DimensionType.builder().ambientLight(0.7f).build());
            var secondInstance = second.instance().createInstanceContainer(dimension, ChunkLoader.noop());
            var shared = second.instance().createSharedInstance(secondInstance);
            var copy = secondInstance.copy();
            assertSame(second, shared.process());
            assertSame(second, copy.process());
            assertSame(second.registries(), shared.registries());
            assertSame(secondInstance.getCachedDimensionType(), shared.getCachedDimensionType());

            var firstEntity = entity(first);
            var secondEntity = entity(second);
            firstEntity.setInstance(firstInstance, Pos.ZERO).join();
            secondEntity.setInstance(shared, Pos.ZERO).join();
            var instanceCalls = new CopyOnWriteArrayList<ServerProcess>();
            var entityCalls = new CopyOnWriteArrayList<ServerProcess>();
            var monitors = new CopyOnWriteArrayList<ServerProcess>();
            firstInstance.eventNode().addListener(InstanceTickEvent.class, (owner, _) -> instanceCalls.add(owner));
            shared.eventNode().addListener(InstanceTickEvent.class, (owner, _) -> instanceCalls.add(owner));
            firstEntity.eventNode().addListener(EntityTickEvent.class, (owner, _) -> entityCalls.add(owner));
            secondEntity.eventNode().addListener(EntityTickEvent.class, (owner, _) -> entityCalls.add(owner));
            first.eventHandler().addListener(ServerTickMonitorEvent.class, (owner, _) -> monitors.add(owner));
            second.eventHandler().addListener(ServerTickMonitorEvent.class, (owner, _) -> monitors.add(owner));

            first.ticker().tick(System.nanoTime());
            assertEquals(List.of(first), instanceCalls);
            assertEquals(List.of(first), entityCalls);
            assertEquals(List.of(first), monitors);
            assertNull(secondEntity.acquirable().assignedThread());
            second.ticker().tick(System.nanoTime());
            assertEquals(List.of(first, second), instanceCalls);
            assertEquals(List.of(first, second), entityCalls);
            assertEquals(List.of(first, second), monitors);
            assertTrue(first.dispatcher().threads().contains(firstEntity.acquirable().assignedThread()));
            assertTrue(second.dispatcher().threads().contains(secondEntity.acquirable().assignedThread()));

            first.close();
            assertFalse(first.dispatcher().isAlive());
            assertThrows(IllegalStateException.class, () -> first.ticker().tick(System.nanoTime()));
            second.ticker().tick(System.nanoTime());
            assertEquals(List.of(first, second, second), entityCalls);
            secondEntity.remove();
            second.ticker().tick(System.nanoTime());
            assertEquals(List.of(first, second, second), entityCalls);
            assertTrue(errors.isEmpty(), errors::toString);
        }
    }

    @Test
    void foreignPlacementAndRegistrationFailBeforeSideEffects() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var source = first.instance().createInstanceContainer(ChunkLoader.noop());
            var destination = second.instance().createInstanceContainer(ChunkLoader.noop());
            var calls = new AtomicInteger();
            first.eventHandler().addListener(AddEntityToInstanceEvent.class, _ -> calls.incrementAndGet());
            second.eventHandler().addListener(AddEntityToInstanceEvent.class, _ -> calls.incrementAndGet());
            var entity = entity(first);
            entity.setInstance(source, Pos.ZERO).join();
            calls.set(0);
            var creature = new EntityCreature(first, EntityType.ZOMBIE);
            var player = new Player(new EmptyConnection(first), new GameProfile(UUID.randomUUID(), "test"));
            assertSame(first, player.process());
            for (var candidate : List.of(entity, creature, player)) {
                assertThrows(IllegalArgumentException.class, () -> candidate.setInstance(destination, new Pos(32, 0, 32)));
            }
            assertSame(source, entity.getInstance());
            assertEquals(Pos.ZERO, entity.getPosition());
            assertTrue(source.getEntities().contains(entity));
            assertNull(creature.getInstance());
            assertNull(player.getInstance());
            assertTrue(destination.getChunks().isEmpty());
            assertTrue(destination.getEntities().isEmpty());
            assertEquals(0, calls.get());
            assertSame(second, destination.getEntityTracker().process());
            assertThrows(IllegalArgumentException.class, () -> destination.getEntityTracker()
                    .register(entity, Pos.ZERO, EntityTracker.Target.ENTITIES, null));
            assertThrows(IllegalArgumentException.class, () -> destination.getEntityTracker()
                    .move(entity, Pos.ZERO, EntityTracker.Target.ENTITIES, null));
            assertThrows(IllegalArgumentException.class, () -> destination.getEntityTracker()
                    .unregister(entity, EntityTracker.Target.ENTITIES, null));
            assertThrows(IllegalArgumentException.class, () -> second.instance().registerInstance(source));
            assertThrows(IllegalArgumentException.class, () -> second.instance().unregisterInstance(source));
            assertThrows(IllegalArgumentException.class, () -> second.instance().createSharedInstance(source));
            var shared = new SharedInstance(UUID.randomUUID(), source);
            assertThrows(IllegalArgumentException.class, () -> second.instance().registerSharedInstance(shared));
            assertTrue(source.getSharedInstances().isEmpty());
            assertTrue(source.isRegistered());
            var foreign = entity(second);
            assertThrows(IllegalArgumentException.class, () -> entity.addPassenger(foreign));
            assertTrue(entity.getPassengers().isEmpty());
            assertNull(foreign.getVehicle());
            assertThrows(IllegalArgumentException.class, () -> new EntityProjectile(first, foreign, EntityType.ARROW));
            assertSame(entity, new EntityProjectile(first, entity, EntityType.ARROW).getShooter());
            var shooterless = new EntityProjectile(second, null, EntityType.ARROW);
            assertSame(second, shooterless.process());
            assertNull(shooterless.getShooter());
            assertThrows(IllegalArgumentException.class, () -> second.dispatcher().createPartition(entity.getChunk()));
            assertThrows(IllegalArgumentException.class, () -> second.dispatcher().updateElement(entity, entity.getChunk()));
            assertThrows(IllegalArgumentException.class, () -> second.dispatcher().removeElement(entity));
        }
    }

    @Test
    void tickFailuresGoToOwner() {
        try (var pair = new ServerProcessPair()) {
            var firstErrors = new CopyOnWriteArrayList<Throwable>();
            var secondErrors = new CopyOnWriteArrayList<Throwable>();
            pair.first().exception().setExceptionHandler(firstErrors::add);
            pair.second().exception().setExceptionHandler(secondErrors::add);
            var expected = new IllegalStateException("tick failure");
            var entity = new Entity(pair.second(), EntityType.ZOMBIE) {
                @Override
                public void tick(long time) {
                    throw expected;
                }
            };
            entity.setInstance(pair.second().instance().createInstanceContainer(ChunkLoader.noop())).join();
            pair.second().ticker().tick(System.nanoTime());
            assertTrue(firstErrors.isEmpty());
            assertEquals(List.of(expected), secondErrors);
        }
    }

    @Test
    void tickWorkerCanCloseItsProcessWithoutDeadlockingTheDispatcher() throws InterruptedException {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var entity = new Entity(first, EntityType.ZOMBIE) {
                @Override
                public void tick(long time) {
                    process().close();
                }
            };
            entity.setInstance(first.instance().createInstanceContainer(ChunkLoader.noop())).join();
            first.ticker().tick(System.nanoTime());
            for (var thread : first.dispatcher().threads()) {
                thread.join(3000);
                assertFalse(thread.isAlive());
            }
            second.ticker().tick(System.nanoTime());
            assertTrue(second.dispatcher().isAlive());
        }
    }

    @Test
    void processStartOwnsTickThreadsAndClosingOneLeavesTheOtherRunning() throws InterruptedException {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var firstTicks = new CountDownLatch(2);
            var secondTicks = new CountDownLatch(2);
            var firstSchedulers = new CopyOnWriteArrayList<String>();
            var secondSchedulers = new CopyOnWriteArrayList<String>();
            first.eventHandler().addListener(ServerTickMonitorEvent.class, _ -> firstSchedulers.add(Thread.currentThread().getName()));
            second.eventHandler().addListener(ServerTickMonitorEvent.class, _ -> secondSchedulers.add(Thread.currentThread().getName()));
            first.eventHandler().addListener(ServerTickMonitorEvent.class, _ -> firstTicks.countDown());
            second.eventHandler().addListener(ServerTickMonitorEvent.class, _ -> secondTicks.countDown());
            first.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            second.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            assertTrue(firstTicks.await(5, TimeUnit.SECONDS));
            assertTrue(secondTicks.await(5, TimeUnit.SECONDS));
            assertNotEquals(first.id(), second.id());
            assertNotEquals(firstSchedulers.getFirst(), secondSchedulers.getFirst());
            assertTrue(firstSchedulers.getFirst().endsWith("-" + first.id()));
            assertTrue(secondSchedulers.getFirst().endsWith("-" + second.id()));
            for (var thread : first.dispatcher().threads()) {
                assertTrue(thread.getName().startsWith("Ms-Tick-" + first.id() + "-"));
                assertTrue(second.dispatcher().threads().stream().noneMatch(other -> other.getName().equals(thread.getName())));
            }
            first.close();
            assertFalse(first.dispatcher().isAlive());
            var remainingTicks = new CountDownLatch(2);
            second.eventHandler().addListener(ServerTickMonitorEvent.class, _ -> remainingTicks.countDown());
            assertTrue(remainingTicks.await(5, TimeUnit.SECONDS));
            assertTrue(second.dispatcher().isAlive());
        }
    }

    @Test
    void loweringTickRateDoesNotDelayByElapsedUptime() throws InterruptedException {
        final int previousRate = ServerProperties.SERVER_TICKS_PER_SECOND.get();
        try {
            ServerProperties.SERVER_TICKS_PER_SECOND.set(20);
            try (var process = ServerProcess.create()) {
                var ticks = new AtomicInteger();
                var resumed = new CountDownLatch(1);
                long[] times = new long[2];
                process.eventHandler().addListener(ServerTickMonitorEvent.class, _ -> {
                    int tick = ticks.incrementAndGet();
                    if (tick == 41) {
                        times[0] = System.nanoTime();
                        ServerProperties.SERVER_TICKS_PER_SECOND.set(10);
                    } else if (tick == 42) {
                        times[1] = System.nanoTime();
                        resumed.countDown();
                    }
                });
                process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                assertTrue(resumed.await(8, TimeUnit.SECONDS));
                long gapMillis = TimeUnit.NANOSECONDS.toMillis(times[1] - times[0]);
                assertTrue(gapMillis < 1000, "Lowering TPS stalled ticking for " + gapMillis + " ms");
            }
        } finally {
            ServerProperties.SERVER_TICKS_PER_SECOND.set(previousRate);
        }
    }

    private static Entity entity(ServerProcess process) {
        var entity = new Entity(process, EntityType.ZOMBIE);
        // Direct delivery to empty viewer sets exercises ticks without packet encoding.
        entity.setAutoViewable(false);
        entity.setNoGravity(true);
        return entity;
    }

    private static final class EmptyConnection extends PlayerConnection {
        EmptyConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(0);
        }
    }
}
