package net.minestom.server.entity;

import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.trait.EntityEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reusable entity configuration which does not belong to a process. Each {@link #spawn(Instance)}
 * constructs a fresh, mutable entity owned by the destination instance's process.
 * <p>
 * Listeners are registered before settings are applied and initializers run. Each entity has its own
 * listener registration state; application state captured by callbacks is not copied.
 * <p>
 * This builder is mutable and not thread-safe. Changes affect subsequent spawns only.
 *
 * @param <T> the constructed entity type
 * @param <B> the concrete builder type, preserved by fluent configuration methods
 */
public abstract class EntityBuilder<T extends Entity, B extends EntityBuilder<T, B>> {
    private final Function<ServerProcess, T> factory;
    private final List<Consumer<? super T>> settings = new ArrayList<>();
    private final List<EventListener<? extends EntityEvent>> listeners = new ArrayList<>();
    private final List<BiConsumer<ServerProcess, ? super T>> initializers = new ArrayList<>();
    private @Nullable Boolean noGravity;
    private @Nullable Boolean autoViewable;
    private @Nullable Vec velocity;

    protected EntityBuilder(Function<ServerProcess, T> factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    /** Returns this builder with its concrete type. */
    @Contract("-> this")
    protected abstract B self();

    /** Adds subtype configuration to apply after common settings and before initializers. */
    @Contract("_ -> this")
    protected final B configure(Consumer<? super T> setting) {
        settings.add(Objects.requireNonNull(setting));
        return self();
    }

    static <T extends Entity> EntityBuilder<T, ?> create(Function<ServerProcess, T> factory) {
        return new Simple<>(factory);
    }

    private static final class Simple<T extends Entity> extends EntityBuilder<T, Simple<T>> {
        private Simple(Function<ServerProcess, T> factory) {
            super(factory);
        }

        @Override
        protected Simple<T> self() {
            return this;
        }
    }

    /** Sets whether the entity is affected by gravity. */
    @Contract("_ -> this")
    public B noGravity(boolean noGravity) {
        this.noGravity = noGravity;
        return self();
    }

    /** Sets whether nearby players automatically become viewers. */
    @Contract("_ -> this")
    public B autoViewable(boolean autoViewable) {
        this.autoViewable = autoViewable;
        return self();
    }

    /** Sets the initial velocity through {@link Entity#setVelocity(Vec)}, including its event. */
    @Contract("_ -> this")
    public B velocity(Vec velocity) {
        this.velocity = Objects.requireNonNull(velocity);
        return self();
    }

    /** Registers a listener on each entity's event node, including its expiration policy. */
    @Contract("_ -> this")
    public B addListener(EventListener<? extends EntityEvent> listener) {
        listeners.add(Objects.requireNonNull(listener));
        return self();
    }

    @Contract("_, _ -> this")
    public <E extends EntityEvent> B addListener(Class<E> eventType, Consumer<E> listener) {
        return addListener(EventListener.of(eventType, listener));
    }

    @Contract("_, _ -> this")
    public <E extends EntityEvent> B addListener(Class<E> eventType, BiConsumer<ServerProcess, E> listener) {
        return addListener(EventListener.of(eventType, listener));
    }

    @Contract("_ -> this")
    public B initialize(Consumer<? super T> initializer) {
        Objects.requireNonNull(initializer);
        return initialize((_, entity) -> initializer.accept(entity));
    }

    /**
     * Adds configuration to run once per created entity, after builder settings and before placement.
     * The entity already has its process, ID and event node, but no instance or tick thread.
     * Initializers run in registration order on the thread calling {@code spawn} and must not place
     * or remove the entity. An initializer failure aborts spawning and removes the entity.
     */
    @Contract("_ -> this")
    public B initialize(BiConsumer<ServerProcess, ? super T> initializer) {
        initializers.add(Objects.requireNonNull(initializer));
        return self();
    }

    /** Spawns a fresh entity at {@link Pos#ZERO}. */
    public CompletableFuture<T> spawn(Instance instance) {
        return spawn(instance, Pos.ZERO);
    }

    /**
     * Creates and configures an entity, then places it in the instance.
     * The future completes with the mutable entity after placement and spawn event dispatch.
     * Creation, initialization and placement failures, including cancelled placement, complete it exceptionally;
     * entities created by this operation are removed on failure.
     * <p>
     * Event callbacks and future continuations follow the normal {@link Entity#setInstance(Instance, Point)}
     * execution context, which may be a chunk loading thread.
     */
    public CompletableFuture<T> spawn(Instance instance, Point position) {
        Objects.requireNonNull(position);
        final ServerProcess process = instance.process();
        final var settings = List.copyOf(this.settings);
        final var listeners = List.copyOf(this.listeners);
        final var initializers = List.copyOf(this.initializers);
        final Boolean noGravity = this.noGravity;
        final Boolean autoViewable = this.autoViewable;
        final Vec velocity = this.velocity;

        final T entity;
        try {
            Check.stateCondition(!instance.isRegistered(), "Instance must be registered before spawning an entity");
            entity = Objects.requireNonNull(factory.apply(process));
            Check.argCondition(entity.process() != process, "Entity factory returned an entity from another process");
            Check.argCondition(entity.getInstance() != null || entity.isRemoved(), "Entity factory must return a fresh, unplaced entity");
        } catch (Throwable failure) {
            process.exceptionManager().handleException(failure);
            return CompletableFuture.failedFuture(failure);
        }

        final CompletableFuture<Void> placement;
        try {
            listeners.forEach(entity.eventNode()::addListener);
            if (noGravity != null) entity.setNoGravity(noGravity);
            if (autoViewable != null) entity.setAutoViewable(autoViewable);
            if (velocity != null) entity.setVelocity(velocity);
            for (var setting : settings) setting.accept(entity);
            for (var initializer : initializers) {
                initializer.accept(process, entity);
                Check.stateCondition(entity.getInstance() != null || entity.isRemoved(), "Initializers must not place or remove the entity");
            }
            placement = entity.setInstance(instance, position);
        } catch (Throwable failure) {
            discard(entity, failure);
            process.exceptionManager().handleException(failure);
            return CompletableFuture.failedFuture(failure);
        }
        return placement.whenComplete((_, failure) -> {
            if (failure != null) discard(entity, failure);
        }).thenApply(_ -> entity);
    }

    private static void discard(Entity entity, Throwable failure) {
        try {
            entity.remove();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
    }
}
