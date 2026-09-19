package net.minestom.server.event;

import net.minestom.server.ServerProcess;
import net.minestom.server.event.trait.AsyncEvent;
import net.minestom.server.event.trait.RecursiveEvent;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

non-sealed class EventNodeImpl<T extends Event> implements EventNode<T> {

    static final Object GLOBAL_CHILD_LOCK = new Object();

    private final Map<Class<?>, Handle<T>> handleMap = new ConcurrentHashMap<>();
    final Map<Class<? extends T>, ListenerEntry<T>> listenerMap = new ConcurrentHashMap<>();
    final Set<EventNodeImpl<T>> children = new CopyOnWriteArraySet<>();

    // Used to store mapped nodes before any listener is added
    // Necessary to avoid creating multiple nodes for the same object
    // Always accessed through the global lock.
    final Map<Object, WeakReference<EventNodeLazyImpl<T>>> mappedNodeCache = new WeakHashMap<>();
    // Store mapped nodes with at least one listener
    // Map is copied and mutated for each new active mapped node
    // Can be considered immutable.
    volatile Map<Object, WeakReference<EventNodeLazyImpl<T>>> registeredMappedNode = new WeakHashMap<>();

    final String name;
    final EventFilter<T, ?> filter;
    final @Nullable EventNode.ContextualPredicate<T, Object> predicate;
    final Class<T> eventType;
    volatile int priority;
    volatile @Nullable EventNodeImpl<? super T> parent;

    EventNodeImpl(String name,
                  EventFilter<T, ?> filter,
                  @Nullable EventNode.ContextualPredicate<T, Object> predicate) {
        this.name = name;
        this.filter = filter;
        this.predicate = predicate;
        this.eventType = filter.eventType();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends T> ListenerHandle<E> getHandle(Class<E> handleType) {
        return (ListenerHandle<E>) handleMap.computeIfAbsent(handleType,
                aClass -> new Handle<>((Class<T>) aClass));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends T> List<EventNode<E>> findChildren(String name, Class<E> eventType) {
        synchronized (GLOBAL_CHILD_LOCK) {
            final Set<EventNode<T>> children = getChildren();
            if (children.isEmpty()) return List.of();
            List<EventNode<E>> result = new ArrayList<>();
            for (EventNode<T> child : children) {
                if (equals(child, name, eventType)) {
                    result.add((EventNode<E>) child);
                }
                result.addAll(child.findChildren(name, eventType));
            }
            return result;
        }
    }

    @Override
    @Contract(pure = true)
    public Set<EventNode<T>> getChildren() {
        return Collections.unmodifiableSet(children);
    }

    @Override
    public <E extends T> void replaceChildren(String name, Class<E> eventType, EventNode<E> eventNode) {
        synchronized (GLOBAL_CHILD_LOCK) {
            final Set<EventNode<T>> children = getChildren();
            if (children.isEmpty()) return;
            for (EventNode<T> child : children) {
                if (equals(child, name, eventType)) {
                    removeChild(child);
                    addChild(eventNode);
                    break;
                }
                child.replaceChildren(name, eventType, eventNode);
            }
        }
    }

    @Override
    public void removeChildren(String name, Class<? extends T> eventType) {
        synchronized (GLOBAL_CHILD_LOCK) {
            final Set<EventNode<T>> children = getChildren();
            if (children.isEmpty()) return;
            for (EventNode<T> child : children) {
                if (equals(child, name, eventType)) {
                    removeChild(child);
                    continue;
                }
                child.removeChildren(name, eventType);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public EventNode<T> addChild(EventNode<? extends T> child) {
        synchronized (GLOBAL_CHILD_LOCK) {
            final var childImpl = (EventNodeImpl<? extends T>) child;
            Check.stateCondition(childImpl.parent != null && childImpl.parent != this, "Node already has a parent");
            Check.argCondition(child instanceof ProcessEventHandler, "An owned root cannot be attached as a child");
            for (EventNodeImpl<?> ancestor = this; ancestor != null; ancestor = ancestor.parent) {
                Check.argCondition(ancestor == childImpl, "Event graph cannot contain a cycle");
            }
            final var process = process();
            if (process != null) childImpl.checkMappedOwners(process);
            if (!children.add((EventNodeImpl<T>) childImpl)) return this; // Couldn't add the child (already present?)
            childImpl.parent = this;
            childImpl.invalidateEventsFor(this);
        }
        return this;
    }

    @Override
    public EventNode<T> removeChild(EventNode<? extends T> child) {
        synchronized (GLOBAL_CHILD_LOCK) {
            final var childImpl = (EventNodeImpl<? extends T>) child;
            final boolean result = this.children.remove(childImpl);
            if (!result) return this; // Child not found
            childImpl.parent = null;
            childImpl.invalidateEventsFor(this);
        }
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public EventNode<T> addListener(EventListener<? extends T> listener) {
        synchronized (GLOBAL_CHILD_LOCK) {
            final var eventType = listener.eventType();
            ListenerEntry<T> entry = getEntry(eventType);
            entry.listeners.add(new RegisteredListener<>((EventListener<T>) listener, (EventListener<T>) listener.newRegistration()));
            invalidateEvent(eventType);
        }
        return this;
    }

    @Override
    public EventNode<T> removeListener(EventListener<? extends T> listener) {
        synchronized (GLOBAL_CHILD_LOCK) {
            final var eventType = listener.eventType();
            ListenerEntry<T> entry = listenerMap.get(eventType);
            if (entry == null) return this; // There is no listener with such type
            for (var registration : entry.listeners) {
                if (registration.listener().equals(listener)) {
                    removeRegistration(registration);
                    break;
                }
            }
        }
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends T, H> EventNode<E> map(H value, EventFilter<E, H> filter) {
        EventNodeImpl<E> node;
        synchronized (GLOBAL_CHILD_LOCK) {
            final var process = process();
            if (process != null) EventOwnership.checkTarget(process, value);
            node = new EventNodeLazyImpl<>(this, value, filter);
            Check.stateCondition(node.parent != null, "Node already has a parent");
            Check.stateCondition(Objects.equals(parent, node), "Cannot map to self");
            WeakReference<EventNodeLazyImpl<T>> previousRef = this.mappedNodeCache.putIfAbsent(value,
                    new WeakReference<>((EventNodeLazyImpl<T>) node));
            EventNodeImpl<T> previous;
            if (previousRef != null && (previous = previousRef.get()) != null) return (EventNode<E>) previous;
            node.parent = this;
        }
        return node;
    }

    @Override
    public void unmap(Object value) {
        synchronized (GLOBAL_CHILD_LOCK) {
            Map<Object, WeakReference<EventNodeLazyImpl<T>>> registered = new WeakHashMap<>(registeredMappedNode);
            final WeakReference<EventNodeLazyImpl<T>> mappedNodeRef = registered.remove(value);
            this.registeredMappedNode = registered;
            EventNodeLazyImpl<T> mappedNode;
            if (mappedNodeRef != null && (mappedNode = mappedNodeRef.get()) != null) {
                mappedNode.invalidateEventsFor(this);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void register(EventBinding<? extends T> binding) {
        synchronized (GLOBAL_CHILD_LOCK) {
            for (var eventType : binding.eventTypes()) {
                @SuppressWarnings("unchecked")
                ListenerEntry<T> entry = getEntry((Class<? extends T>) eventType);
                @SuppressWarnings("unchecked") final boolean added = entry.bindings.add((EventBinding<T>) binding);
                if (added) invalidateEvent((Class<? extends T>) eventType);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void unregister(EventBinding<? extends T> binding) {
        synchronized (GLOBAL_CHILD_LOCK) {
            for (var eventType : binding.eventTypes()) {
                ListenerEntry<T> entry = listenerMap.get(eventType);
                if (entry == null) return;
                final boolean removed = entry.bindings.remove(binding);
                if (removed) invalidateEvent((Class<? extends T>) eventType);
            }
        }
    }

    @Override
    public Class<T> getEventType() {
        return eventType;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public EventNode<T> setPriority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public @Nullable ServerProcess process() {
        final var parent = this.parent;
        return parent != null ? parent.process() : null;
    }

    @Override
    public @Nullable EventNode<? super T> getParent() {
        return parent;
    }

    @Override
    public String toString() {
        return createStringGraph(createGraph());
    }

    Graph createGraph() {
        synchronized (GLOBAL_CHILD_LOCK) {
            List<Graph> children = this.children.stream().map(EventNodeImpl::createGraph).toList();
            return new Graph(getName(), getEventType().getSimpleName(), getPriority(), children);
        }
    }

    static String createStringGraph(Graph graph) {
        StringBuilder buffer = new StringBuilder();
        genToStringTree(buffer, "", "", graph);
        return buffer.toString();
    }

    private static void genToStringTree(StringBuilder buffer, String prefix, String childrenPrefix, Graph graph) {
        buffer.append(prefix);
        buffer.append(String.format("%s - EventType: %s - Priority: %d", graph.name(), graph.eventType(), graph.priority()));
        buffer.append('\n');
        var nextNodes = graph.children();
        for (Iterator<? extends Graph> iterator = nextNodes.iterator(); iterator.hasNext(); ) {
            Graph next = iterator.next();
            if (iterator.hasNext()) {
                genToStringTree(buffer, childrenPrefix + '├' + '─' + " ", childrenPrefix + '│' + "   ", next);
            } else {
                genToStringTree(buffer, childrenPrefix + '└' + '─' + " ", childrenPrefix + "    ", next);
            }
        }
    }

    record Graph(String name, String eventType, int priority,
                 List<Graph> children) {
        public Graph {
            children = children.stream().sorted(Comparator.comparingInt(Graph::priority)).toList();
        }
    }

    void invalidateEventsFor(EventNodeImpl<? super T> node) {
        assert Thread.holdsLock(GLOBAL_CHILD_LOCK);
        for (Class<? extends T> eventType : listenerMap.keySet()) {
            node.invalidateEvent(eventType);
        }
        for (var reference : registeredMappedNode.values()) {
            var mapped = reference.get();
            if (mapped != null) mapped.invalidateEventsFor(node);
        }
        for (EventNodeImpl<T> child : children) {
            child.invalidateEventsFor(node);
        }
    }

    @SuppressWarnings("unchecked")
    private void invalidateEvent(Class<? extends T> eventClass) {
        forTargetEvents(eventClass, type -> {
            Handle<T> handle = handleMap.computeIfAbsent(type,
                    aClass -> new Handle<>((Class<T>) aClass));
            handle.invalidate();
        });
        invalidateRecursiveSuperclasses(eventClass);
        final EventNodeImpl<? super T> parent = this.parent;
        if (parent != null) parent.invalidateEvent(eventClass);
    }

    private void invalidateRecursiveSuperclasses(Class<?> eventClass) {
        if (RecursiveEvent.class.isAssignableFrom(eventClass)) {
            for (var cls : this.handleMap.keySet()) {
                if (eventClass.isAssignableFrom(cls)) {
                    this.handleMap.get(cls).invalidate();
                }
            }
        }
    }

    private void checkMappedOwners(ServerProcess process) {
        for (var value : mappedNodeCache.keySet()) EventOwnership.checkTarget(process, value);
        for (var reference : mappedNodeCache.values()) {
            var node = reference.get();
            if (node != null) ((EventNodeImpl<?>) node).checkMappedOwners(process);
        }
        for (var child : children) child.checkMappedOwners(process);
    }

    private ListenerEntry<T> getEntry(Class<? extends T> type) {
        return listenerMap.computeIfAbsent(type, _ -> new ListenerEntry<>());
    }

    private static boolean equals(EventNode<?> node, String name, Class<?> eventType) {
        return node.getName().equals(name) && eventType.isAssignableFrom(node.getEventType());
    }

    private static void forTargetEvents(Class<?> type, Consumer<Class<?>> consumer) {
        consumer.accept(type);
        // Recursion
        if (RecursiveEvent.class.isAssignableFrom(type)) {
            final Class<?> superclass = type.getSuperclass();
            if (superclass != null && RecursiveEvent.class.isAssignableFrom(superclass)) {
                forTargetEvents(superclass, consumer);
            }
        }
    }

    private void removeRegistration(RegisteredListener<T> registration) {
        synchronized (GLOBAL_CHILD_LOCK) {
            final var eventType = registration.listener().eventType();
            final var entry = listenerMap.get(eventType);
            if (entry != null && entry.listeners.remove(registration)) invalidateEvent(eventType);
        }
    }

    private record RegisteredListener<T extends Event>(EventListener<T> listener, EventListener<T> state) {
    }

    private static class ListenerEntry<T extends Event> {
        final List<RegisteredListener<T>> listeners = new CopyOnWriteArrayList<>();
        final Set<EventBinding<T>> bindings = new CopyOnWriteArraySet<>();
    }

    @SuppressWarnings("unchecked")
    final class Handle<E extends Event> implements ListenerHandle<E> {
        private final Class<E> eventType;
        private @Nullable BiConsumer<ServerProcess, E> listener = null;
        private volatile boolean updated;

        Handle(Class<E> eventType) {
            this.eventType = eventType;
        }

        @Override
        public void call(E event) {
            final var process = EventNodeImpl.this.process();
            Check.stateCondition(process == null, "Standalone event dispatch requires a process");
            call(process, event);
        }

        @Override
        public void call(ServerProcess process, E event) {
            Objects.requireNonNull(process);
            EventOwnership.checkEvent(process, event);
            assert !(event instanceof AsyncEvent) || Thread.currentThread().isVirtual() :
                    "AsyncEvent must be called within a Virtual Thread, got " + Thread.currentThread();
            dispatch(process, event);
        }

        void dispatch(ServerProcess process, E event) {
            final var owner = EventNodeImpl.this.process();
            Check.argCondition(owner != null && owner != process, "Event node belongs to another process");
            if (EventNodeImpl.this instanceof EventNodeLazyImpl<?> mapped) mapped.checkOwner(process);
            final BiConsumer<ServerProcess, E> listener = updatedListener();
            if (listener == null) return;
            try {
                listener.accept(process, event);
            } catch (Throwable e) {
                process.exceptionManager().handleException(e);
            }
        }

        @Override
        public boolean hasListener() {
            return updatedListener() != null;
        }

        void invalidate() {
            this.updated = false;
            this.listener = null;
        }

        @Nullable BiConsumer<ServerProcess, E> updatedListener() {
            if (updated) return listener;
            synchronized (GLOBAL_CHILD_LOCK) {
                if (updated) return listener;
                final BiConsumer<ServerProcess, E> listener = createConsumer();
                this.listener = listener;
                this.updated = true;
                return listener;
            }
        }

        private @Nullable BiConsumer<ServerProcess, E> createConsumer() {
            var node = (EventNodeImpl<E>) EventNodeImpl.this;
            // Standalone listeners
            List<BiConsumer<ServerProcess, E>> listeners = new ArrayList<>();
            forTargetEvents(eventType, type -> {
                final ListenerEntry<E> entry = node.listenerMap.get(type);
                if (entry != null) {
                    final BiConsumer<ServerProcess, E> result = listenersConsumer(entry, type);
                    if (result != null) listeners.add(result);
                }
            });
            final BiConsumer<ServerProcess, E>[] listenersArray = listeners.toArray(BiConsumer[]::new);
            // Mapped
            final BiConsumer<ServerProcess, E> mappedListener = mappedConsumer();
            // Children
            final BiConsumer<ServerProcess, E>[] childrenListeners = node.children.stream()
                    .filter(child -> child.eventType.isAssignableFrom(eventType)) // Invalid event type
                    .sorted(Comparator.comparingInt(EventNode::getPriority))
                    .map(child -> ((Handle<E>) child.getHandle(eventType)).updatedListener())
                    .filter(Objects::nonNull)
                    .toArray(BiConsumer[]::new);
            // Empty check
            final EventNode.ContextualPredicate<E, Object> predicate = node.predicate;
            final EventFilter<E, ?> filter = node.filter;
            final boolean hasPredicate = predicate != null;
            final boolean hasListeners = listenersArray.length > 0;
            final boolean hasMap = mappedListener != null;
            final boolean hasChildren = childrenListeners.length > 0;
            if (!hasListeners && !hasMap && !hasChildren) {
                // No listener
                return null;
            }
            return (process, e) -> {
                // Filtering
                if (hasPredicate) {
                    final Object value = filter.getHandler(process, e);
                    if (!predicate.test(process, e, value)) return;
                }
                // Normal listeners
                if (hasListeners) {
                    for (BiConsumer<ServerProcess, E> listener : listenersArray) {
                        listener.accept(process, e);
                    }
                }
                // Mapped nodes
                if (hasMap) mappedListener.accept(process, e);
                // Children
                if (hasChildren) {
                    for (BiConsumer<ServerProcess, E> childHandle : childrenListeners) {
                        childHandle.accept(process, e);
                    }
                }
            };
        }

        /**
         * Create a consumer calling all listeners from {@link EventNode#addListener(EventListener)} and
         * {@link EventNode#register(EventBinding)}.
         * <p>
         * Most computation should ideally be done outside the consumers as a one-time cost.
         */
        private @Nullable BiConsumer<ServerProcess, E> listenersConsumer(ListenerEntry<E> entry, Class<?> type) {
            final RegisteredListener<E>[] listenersCopy = entry.listeners.toArray(RegisteredListener[]::new);
            final BiConsumer<ServerProcess, E>[] bindingsCopy = entry.bindings.stream().map(binding -> binding.consumer(type.asSubclass(Event.class))).toArray(BiConsumer[]::new);
            final boolean listenersEmpty = listenersCopy.length == 0;
            final boolean bindingsEmpty = bindingsCopy.length == 0;
            if (listenersEmpty && bindingsEmpty) return null;
            if (bindingsEmpty && listenersCopy.length == 1) {
                // Only one normal listener
                final RegisteredListener<E> listener = listenersCopy[0];
                return (process, e) -> callListener(process, listener, e);
            }
            // Worse case scenario, try to run everything
            return (process, e) -> {
                if (!listenersEmpty) {
                    for (RegisteredListener<E> listener : listenersCopy) {
                        callListener(process, listener, e);
                    }
                }
                if (!bindingsEmpty) {
                    for (BiConsumer<ServerProcess, E> eConsumer : bindingsCopy) {
                        eConsumer.accept(process, e);
                    }
                }
            };
        }

        /**
         * Create a consumer handling {@link EventNode#map(Object, EventFilter)}.
         * The goal is to limit the amount of map lookup.
         */
        private @Nullable BiConsumer<ServerProcess, E> mappedConsumer() {
            var node = (EventNodeImpl<E>) EventNodeImpl.this;
            final var mappedNodeCache = node.registeredMappedNode;
            if (mappedNodeCache.isEmpty()) return null;
            Set<EventFilter<E, ?>> filters = new HashSet<>(mappedNodeCache.size());
            Map<Object, WeakReference<Handle<E>>> handlers = new WeakHashMap<>(mappedNodeCache.size());

            // Retrieve all filters used to retrieve potential handlers
            for (var mappedEntry : mappedNodeCache.entrySet()) {
                final WeakReference<EventNodeLazyImpl<E>> mappedNodeRef = mappedEntry.getValue();
                final EventNodeLazyImpl<E> mappedNode = mappedNodeRef.get();
                if (mappedNode == null) continue; // Weak reference collected
                final Handle<E> handle = (Handle<E>) mappedNode.getHandle(eventType);
                if (!handle.hasListener()) continue; // Implicit update
                filters.add(mappedNode.filter);
                handlers.put(mappedEntry.getKey(), new WeakReference<>(handle));
            }
            // If at least one mapped node listen to this handle type,
            // loop through them and forward to mapped node if there is a match
            if (filters.isEmpty()) return null;
            final EventFilter<E, ?>[] filterList = filters.toArray(EventFilter[]::new);
            return (process, event) -> {
                for (var filter : filterList) {
                    final Object handler = filter.castHandler(process, event);
                    final WeakReference<Handle<E>> handleRef = handlers.get(handler);
                    final Handle<E> handle = handleRef != null ? handleRef.get() : null;
                    if (handle != null) handle.dispatch(process, event);
                }
            };
        }

        void callListener(ServerProcess process, RegisteredListener<E> listener, E event) {
            var node = (EventNodeImpl<E>) EventNodeImpl.this;
            EventListener.Result result = listener.state().run(process, event);
            if (result == EventListener.Result.EXPIRED) {
                node.removeRegistration(listener);
                invalidate();
            }
        }
    }
}
