package net.minestom.server.event;

import net.minestom.server.ServerProcess;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.event.entity.EntityTickEvent;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventProcessTest {
    record TestEvent() implements Event {
    }

    @Test
    void contextReachesFiltersListenersMappingsBindingsAndCachedHandles() {
        var calls = new ArrayList<ServerProcess>();
        var expectedProcess = new AtomicReference<ServerProcess>();
        var filter = EventFilter.from(TestEvent.class, ServerProcess.class, (process, _) -> process);
        var node = EventNode.type("standalone", filter, (process, _, value) -> process == value);
        node.addListener(TestEvent.class, (process, _) -> calls.add(process));
        var child = EventNode.event("child", filter, (process, _) -> process == expectedProcess.get());
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
            node.map(entity, EventFilter.ENTITY).addListener(EntityTickEvent.class, _ -> calls.incrementAndGet());
            var handle = first.eventHandler().getHandle(EntityTickEvent.class);
            assertFalse(handle.hasListener());
            assertThrows(IllegalArgumentException.class, () -> second.eventHandler().addChild(node));
            assertThrows(IllegalArgumentException.class, () -> second.eventHandler().map(entity, EventFilter.ENTITY));
            first.eventHandler().addChild(node);
            handle.call(new EntityTickEvent(entity));
            assertEquals(1, calls.get());
            first.eventHandler().removeChild(node);
            handle.call(new EntityTickEvent(entity));
            assertEquals(1, calls.get());
            assertThrows(IllegalArgumentException.class, () -> second.eventHandler().call(new EntityTickEvent(entity)));
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
            var filter = EventFilter.from(EventNodeTest.Recursive1.class, ServerProcess.class, (process, _) -> process);
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
