package net.minestom.server.event;

import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.entity.EntityPotionAddEvent;
import net.minestom.server.event.entity.EntityPotionRemoveEvent;
import net.minestom.server.event.entity.EntityShootEvent;
import net.minestom.server.event.entity.EntitySpawnEvent;
import net.minestom.server.event.entity.EntityTickEvent;
import net.minestom.server.event.entity.EntityVelocityEvent;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.item.EntityEquipEvent;
import net.minestom.server.event.server.ClientPingServerEvent;
import net.minestom.server.event.trait.EntityEvent;
import net.minestom.server.event.trait.EntityInstanceEvent;
import net.minestom.server.event.trait.InstanceEvent;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventProcessTest {
    record TestEvent() implements Event {
    }

    record TargetEvent(Object target) implements Event {
    }

    record InstanceTargetEvent(Entity getEntity, Instance getInstance) implements EntityInstanceEvent {
    }

    @Test
    void mappingsRejectForeignProcessOwnedTargetsBeforeAttachment() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var instance = second.instance().createInstanceContainer(ChunkLoader.noop());
            var filter = EventFilter.from(TargetEvent.class, Object.class, TargetEvent::target);
            for (var target : List.of(second, second.eventHandler(), second.entity(), second.instance(), second.connection(), second.server(),
                    instance, instance.getEntityTracker(), new DynamicChunk(instance, 0, 0),
                    new Entity(second, EntityType.ZOMBIE), connection(second))) {
                assertThrows(IllegalArgumentException.class, () -> first.eventHandler().map(target, filter));
                var node = EventNode.all("mapped-before-attachment");
                var mapped = node.map(target, filter);
                assertThrows(IllegalArgumentException.class, () -> first.eventHandler().addChild(node));
                assertNull(node.getParent());
                second.eventHandler().addChild(node);
                var calls = new AtomicInteger();
                mapped.addListener(TargetEvent.class, _ -> calls.incrementAndGet());
                second.eventHandler().call(new TargetEvent(target));
                assertEquals(1, calls.get());
                second.eventHandler().removeChild(node);
            }
        }
    }

    @Test
    void configurationEventsFireBeforePlacementWithoutClaimingAnInstance() {
        try (var pair = new ServerProcessPair()) {
            var process = pair.first();
            var errors = new ArrayList<Throwable>();
            process.exception().setExceptionHandler(errors::add);
            var entity = new LivingEntity(process, EntityType.ZOMBIE);
            entity.setAutoViewable(false);
            var calls = new ArrayList<Class<?>>();
            var foreignCalls = new AtomicInteger();
            List<Class<? extends EntityEvent>> types = List.of(EntityVelocityEvent.class,
                    EntityEquipEvent.class, EntityPotionAddEvent.class, EntityPotionRemoveEvent.class);
            for (var type : types) {
                entity.eventNode().addListener(type, (owner, event) -> {
                    assertSame(process, owner);
                    assertSame(entity, event.getEntity());
                    assertFalse(event instanceof InstanceEvent);
                    calls.add(event.getClass());
                });
                pair.second().eventHandler().addListener(type, _ -> foreignCalls.incrementAndGet());
            }
            entity.setVelocity(new Vec(1, 0, 0));
            entity.setItemInMainHand(ItemStack.of(Material.STONE));
            entity.addEffect(new Potion(PotionEffect.SPEED, 0, 20));
            entity.removeEffect(PotionEffect.SPEED);
            assertEquals(types, calls);
            assertNull(entity.getInstance());
            assertThrows(NullPointerException.class, () -> new EntityTickEvent(entity).getInstance());

            var instance = process.instance().createInstanceContainer(ChunkLoader.noop());
            var instanceCalls = new AtomicInteger();
            instance.eventNode().addListener(EntitySpawnEvent.class, event -> {
                assertSame(instance, event.getInstance());
                instanceCalls.incrementAndGet();
            });
            instance.eventNode().addListener(EntityTickEvent.class, event -> {
                assertSame(instance, event.getInstance());
                instanceCalls.incrementAndGet();
            });
            entity.setInstance(instance).join();
            process.eventHandler().call(new EntityTickEvent(entity));
            assertEquals(2, instanceCalls.get());
            assertEquals(0, foreignCalls.get());
            assertTrue(errors.isEmpty(), errors::toString);
        }
    }

    @Test
    void cachedMappingsValidateTheirTargetsCurrentOwner() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var target = EventNode.all("target");
            var filter = EventFilter.from(TargetEvent.class, Object.class, TargetEvent::target);
            var mapped = first.eventHandler().map(target, filter);
            var calls = new AtomicInteger();
            var errors = new ArrayList<Throwable>();
            first.exception().setExceptionHandler(errors::add);
            mapped.addListener(TargetEvent.class, _ -> calls.incrementAndGet());
            var handle = first.eventHandler().getHandle(TargetEvent.class);
            var mappedHandle = mapped.getHandle(TargetEvent.class);
            var event = new TargetEvent(target);
            handle.call(event);
            assertEquals(1, calls.get());

            second.eventHandler().addChild(target);
            handle.call(event);
            assertEquals(1, calls.get());
            assertEquals(1, errors.size());
            assertInstanceOf(IllegalArgumentException.class, errors.getFirst());
            assertThrows(IllegalArgumentException.class, () -> mappedHandle.call(event));

            second.eventHandler().removeChild(target);
            first.eventHandler().addChild(target);
            handle.call(event);
            assertEquals(2, calls.get());
        }
    }

    @Test
    void dispatchValidatesExplicitInstancesAndSecondaryTargetsBeforeListeners() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var local = new Entity(first, EntityType.ZOMBIE);
            var foreign = new Entity(second, EntityType.ZOMBIE);
            var localInstance = first.instance().createInstanceContainer(ChunkLoader.noop());
            var foreignInstance = second.instance().createInstanceContainer(ChunkLoader.noop());
            List<Event> invalid = List.of(
                    new InstanceTargetEvent(local, foreignInstance),
                    new EntitySpawnEvent(local, foreignInstance),
                    new EntityAttackEvent(local, foreign),
                    new EntityShootEvent(local, foreign, Pos.ZERO, 1, 0),
                    new InstanceChunkLoadEvent(localInstance, new DynamicChunk(foreignInstance, 0, 0)),
                    new ClientPingServerEvent(connection(second), 0));
            var calls = new AtomicInteger();
            for (var event : invalid) {
                first.eventHandler().addListener(event.getClass(), _ -> calls.incrementAndGet());
                assertThrows(IllegalArgumentException.class, () -> first.eventHandler().call(event));
            }
            assertEquals(0, calls.get());

            first.eventHandler().addListener(EntityVelocityEvent.class, event -> {
                assertNull(event.getEntity().getInstance());
                calls.incrementAndGet();
            });
            local.setVelocity(Vec.ZERO);
            assertEquals(1, calls.get());
        }
    }

    private static PlayerConnection connection(ServerProcess process) {
        return new PlayerConnection(process) {
            @Override
            public void sendPacket(SendablePacket packet) {
            }

            @Override
            public SocketAddress getRemoteAddress() {
                return new InetSocketAddress(0);
            }
        };
    }

    @Test
    void contextReachesFiltersListenersMappingsBindingsAndCachedHandles() {
        var calls = new ArrayList<ServerProcess>();
        var expectedProcess = new AtomicReference<ServerProcess>();
        var filter = EventFilter.fromContextual(TestEvent.class, ServerProcess.class, (process, _) -> process);
        var node = EventNode.contextual("standalone", filter, (process, _, value) -> process == value);
        node.addListener(TestEvent.class, (process, _) -> calls.add(process));
        var child = EventNode.contextual("child", filter, (process, _, _) -> process == expectedProcess.get());
        child.addListener(EventListener.builder(TestEvent.class)
                .filter((process, _) -> process == expectedProcess.get())
                .expireWhen((process, _) -> process != expectedProcess.get())
                .handler((process, _) -> calls.add(process)).build());
        node.addChild(child);
        var handle = node.getHandle(TestEvent.class);
        assertThrows(IllegalStateException.class, () -> handle.call(new TestEvent()));

        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var firstMap = node.map(first, filter);
            var secondMap = node.map(second, filter);
            firstMap.addListener(TestEvent.class, (process, _) -> calls.add(process));
            secondMap.addListener(TestEvent.class, (process, _) -> calls.add(process));
            var binding = EventBinding.filtered(filter, _ -> true)
                    .map(TestEvent.class, (process, _) -> calls.add(process)).build();
            node.register(binding);

            expectedProcess.set(first);
            handle.call(first, new TestEvent());
            expectedProcess.set(second);
            handle.call(second, new TestEvent());
            assertEquals(List.of(first, first, first, first, second, second, second, second), calls);

            node.unregister(binding);
            node.unmap(first);
            calls.clear();
            expectedProcess.set(first);
            handle.call(first, new TestEvent());
            assertEquals(List.of(first, first), calls);
        }
    }

    @Test
    void registrationHasOneRootAndCachedHandlesFollowReattachment() {
        var node = EventNode.all("configured-before-process");
        var calls = new ArrayList<ServerProcess>();
        node.addListener(TestEvent.class, (process, _) -> calls.add(process));
        var handle = node.getHandle(TestEvent.class);
        assertTrue(handle.hasListener());
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var firstHandle = first.eventHandler().getHandle(TestEvent.class);
            var secondHandle = second.eventHandler().getHandle(TestEvent.class);
            first.eventHandler().addChild(node);
            assertThrows(IllegalStateException.class, () -> second.eventHandler().addChild(node));
            assertThrows(IllegalArgumentException.class, () -> handle.call(second, new TestEvent()));
            assertThrows(IllegalArgumentException.class, () -> node.addChild(first.eventHandler()));
            firstHandle.call(new TestEvent());
            secondHandle.call(new TestEvent());
            assertEquals(List.of(first), calls);

            first.eventHandler().removeChild(node);
            assertThrows(IllegalStateException.class, () -> handle.call(new TestEvent()));
            second.eventHandler().addChild(node);
            firstHandle.call(new TestEvent());
            secondHandle.call(new TestEvent());
            handle.call(new TestEvent());
            assertEquals(List.of(first, second, second), calls);
        }
    }

    @Test
    void mappedSubtreeInvalidationAndOwnerChecks() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var entity = new Entity(first, EntityType.ZOMBIE);
            var calls = new AtomicInteger();
            var node = EventNode.all("subtree");
            node.map(entity, EventFilter.ENTITY).addListener(EntityVelocityEvent.class, _ -> calls.incrementAndGet());
            var handle = first.eventHandler().getHandle(EntityVelocityEvent.class);
            assertFalse(handle.hasListener());
            assertThrows(IllegalArgumentException.class, () -> second.eventHandler().addChild(node));
            assertThrows(IllegalArgumentException.class, () -> second.eventHandler().map(entity, EventFilter.ENTITY));
            first.eventHandler().addChild(node);
            handle.call(new EntityVelocityEvent(entity, Vec.ZERO));
            assertEquals(1, calls.get());
            first.eventHandler().removeChild(node);
            handle.call(new EntityVelocityEvent(entity, Vec.ZERO));
            assertEquals(1, calls.get());
            assertThrows(IllegalArgumentException.class, () -> second.eventHandler().call(new EntityVelocityEvent(entity, Vec.ZERO)));
        }
    }

    @Test
    void expirationCountsBelongToEachRegistration() {
        var calls = new ArrayList<ServerProcess>();
        var listener = EventListener.builder(TestEvent.class).expireCount(1)
                .handler((process, _) -> calls.add(process)).build();
        try (var pair = new ServerProcessPair()) {
            pair.first().eventHandler().addListener(listener);
            pair.second().eventHandler().addListener(listener);
            pair.first().eventHandler().call(new TestEvent());
            pair.second().eventHandler().call(new TestEvent());
            pair.first().eventHandler().call(new TestEvent());
            pair.second().eventHandler().call(new TestEvent());
            assertEquals(List.of(pair.first(), pair.second()), calls);
        }
    }

    @Test
    void recursiveBindingsReceiveDispatchContext() {
        try (var pair = new ServerProcessPair()) {
            var calls = new ArrayList<ServerProcess>();
            var filter = EventFilter.fromContextual(EventNodeTest.Recursive1.class, ServerProcess.class, (process, _) -> process);
            var binding = EventBinding.filtered(filter, _ -> true)
                    .map(EventNodeTest.Recursive1.class, (process, _) -> calls.add(process)).build();
            var node = EventNode.all("recursive");
            node.register(binding);
            var handle = node.getHandle(EventNodeTest.Recursive2.class);
            handle.call(pair.first(), new EventNodeTest.Recursive2());
            handle.call(pair.second(), new EventNodeTest.Recursive2());
            assertEquals(List.of(pair.first(), pair.second()), calls);
        }
    }

    @Test
    void exceptionsAndExpiringListenersStayWithTheirRoot() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var firstErrors = new ArrayList<Throwable>();
            var secondErrors = new ArrayList<Throwable>();
            first.exception().setExceptionHandler(firstErrors::add);
            second.exception().setExceptionHandler(secondErrors::add);
            var expected = new IllegalStateException("listener failure");
            first.eventHandler().addListener(TestEvent.class, (_, _) -> { throw expected; });
            var calls = new AtomicInteger();
            second.eventHandler().addListener(EventListener.builder(TestEvent.class).expireCount(1)
                    .handler((_, _) -> calls.incrementAndGet()).build());
            first.eventHandler().call(new TestEvent());
            second.eventHandler().call(new TestEvent());
            second.eventHandler().call(new TestEvent());
            assertEquals(List.of(expected), firstErrors);
            assertTrue(secondErrors.isEmpty());
            assertEquals(1, calls.get());
            assertFalse(second.eventHandler().hasListener(TestEvent.class));
        }
    }
}
