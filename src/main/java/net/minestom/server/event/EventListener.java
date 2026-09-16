package net.minestom.server.event;

import net.minestom.server.ServerProcess;
import net.minestom.server.event.trait.CancellableEvent;
import net.minestom.server.event.trait.RecursiveEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Represents an event listener (handler) in an event graph.
 * <p>
 * A listener is responsible for executing some action based on an event triggering.
 *
 * @param <T> The event type being handled.
 */
public interface EventListener<T extends Event> {

    Class<T> eventType();

    /** Executes the listener with the dispatching process, including ordinary listener callbacks. */
    Result run(ServerProcess process, T event);

    /**
     * Creates the state used by one node registration. Custom stateful listeners should override
     * this method when their registration state must be copied; captured application state remains caller-owned.
     */
    @ApiStatus.Internal
    default EventListener<T> newRegistration() {
        return this;
    }

    @Contract(pure = true)
    static <T extends Event> EventListener.Builder<T> builder(Class<T> eventType) {
        return new EventListener.Builder<>(eventType);
    }

    /**
     * Create an event listener without any special options. The given listener will be executed
     * if the event passes all parent filtering.
     *
     * @param eventType The event type to handle
     * @param listener  The handler function
     * @param <T>       The event type to handle
     * @return An event listener with the given properties
     */
    @Contract(pure = true)
    static <T extends Event> EventListener<T> of(Class<T> eventType, Consumer<T> listener) {
        return of(eventType, (_, event) -> listener.accept(event));
    }

    @Contract(pure = true)
    static <T extends Event> EventListener<T> of(Class<T> eventType, BiConsumer<ServerProcess, T> listener) {
        if (CancellableEvent.class.isAssignableFrom(eventType) || RecursiveEvent.class.isAssignableFrom(eventType)) {
            return new Builder.ListenerImpl<>(eventType, (process, event) -> {
                if (event instanceof CancellableEvent cancellableEvent && cancellableEvent.isCancelled())
                    return Result.INVALID;
                listener.accept(process, event);
                return Result.SUCCESS;
            }, 0);
        }
        return new Builder.ListenerImpl<>(eventType, (process, event) -> {
            listener.accept(process, event);
            return Result.SUCCESS;
        }, 0);
    }

    class Builder<T extends Event> {
        private static final class ListenerImpl<T extends Event> implements EventListener<T> {
            private final Class<T> eventType;
            private final BiFunction<ServerProcess, T, Result> function;
            private final int expireCount;
            private final AtomicInteger remaining;

            private ListenerImpl(Class<T> eventType, BiFunction<ServerProcess, T, Result> function, int expireCount) {
                this.eventType = eventType;
                this.function = function;
                this.expireCount = expireCount;
                this.remaining = new AtomicInteger(expireCount);
            }

            @Override
            public Class<T> eventType() {
                return eventType;
            }

            @Override
            public EventListener<T> newRegistration() {
                return expireCount > 0 ? new ListenerImpl<>(eventType, function, expireCount) : this;
            }

            @Override
            public Result run(ServerProcess process, T event) {
                final var result = function.apply(process, event);
                if (result == Result.SUCCESS && expireCount > 0 && remaining.decrementAndGet() == 0)
                    return Result.EXPIRED;
                return result;
            }
        }

        private final Class<T> eventType;
        private final List<BiPredicate<ServerProcess, T>> filters = new ArrayList<>();
        private boolean ignoreCancelled = true;
        private int expireCount;
        private BiPredicate<ServerProcess, T> expireWhen;
        private BiConsumer<ServerProcess, T> handler;

        protected Builder(Class<T> eventType) {
            this.eventType = eventType;
        }

        /**
         * Adds a filter to the executor of this listener. The executor will only
         * be called if this condition passes on the given event.
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> filter(Predicate<T> filter) {
            return filter((_, event) -> filter.test(event));
        }

        @Contract(value = "_ -> this")
        public EventListener.Builder<T> filter(BiPredicate<ServerProcess, T> filter) {
            this.filters.add(filter);
            return this;
        }

        /**
         * Specifies if the handler should still be called if {@link CancellableEvent#isCancelled()} returns {@code true}.
         * <p>
         * Default is set to {@code true}.
         *
         * @param ignoreCancelled True to stop processing the event when cancelled
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> ignoreCancelled(boolean ignoreCancelled) {
            this.ignoreCancelled = ignoreCancelled;
            return this;
        }

        /**
         * Removes this listener after it has been executed the given number of times.
         *
         * @param expireCount The number of times to execute
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> expireCount(int expireCount) {
            this.expireCount = expireCount;
            return this;
        }

        /**
         * Expires this listener when it passes the given condition. The expiration will
         * happen before the event is executed.
         *
         * @param expireWhen The condition to test
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> expireWhen(Predicate<T> expireWhen) {
            return expireWhen((_, event) -> expireWhen.test(event));
        }

        @Contract(value = "_ -> this")
        public EventListener.Builder<T> expireWhen(BiPredicate<ServerProcess, T> expireWhen) {
            this.expireWhen = expireWhen;
            return this;
        }

        /**
         * Sets the handler for this event listener. This will be executed if the listener passes
         * all conditions.
         */
        @Contract(value = "_ -> this")
        public EventListener.Builder<T> handler(Consumer<T> handler) {
            return handler((_, event) -> handler.accept(event));
        }

        @Contract(value = "_ -> this")
        public EventListener.Builder<T> handler(BiConsumer<ServerProcess, T> handler) {
            this.handler = handler;
            return this;
        }

        @Contract(value = "-> new", pure = true)
        public EventListener<T> build() {
            final boolean ignoreCancelled = this.ignoreCancelled;

            final BiPredicate<ServerProcess, T> expireWhen = this.expireWhen;

            final var filters = new ArrayList<>(this.filters);
            final var handler = this.handler;
            return new ListenerImpl<>(eventType, (process, event) -> {
                // Event cancellation
                if (ignoreCancelled && event instanceof CancellableEvent cancellableEvent &&
                        cancellableEvent.isCancelled()) {
                    return Result.INVALID;
                }
                // Expiration predicate
                if (expireWhen != null && expireWhen.test(process, event)) {
                    return Result.EXPIRED;
                }
                // Filtering
                if (!filters.isEmpty()) {
                    for (var filter : filters) {
                        if (!filter.test(process, event)) {
                            // Cancelled
                            return Result.INVALID;
                        }
                    }
                }
                // Handler
                if (handler != null) {
                    handler.accept(process, event);
                }
                return Result.SUCCESS;
            }, expireCount);
        }
    }

    enum Result {
        SUCCESS,
        INVALID,
        EXPIRED,
        EXCEPTION
    }
}
