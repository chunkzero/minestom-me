package net.minestom.server.registry;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

final class RegistryTagImpl {

    record Empty() implements RegistryTag<Object> {
        public static final Empty INSTANCE = new Empty();

        @Override
        public @Nullable TagKey<Object> key() {
            return null;
        }
    }

    record Reference<T>(TagKey<T> key) implements RegistryTag<T> {
        Reference {
            Objects.requireNonNull(key);
        }
    }

    /**
     * A tag that is backed by a registry.
     */
    static final class Backed<T> implements Iterable<RegistryKey<T>> {
        private final Reference<T> reference;
        private final Set<RegistryKey<T>> entries = new CopyOnWriteArraySet<>();
        private final Set<RegistryKey<T>> entriesView = Collections.unmodifiableSet(entries);
        private final Runnable onChange;

        Backed(TagKey<T> key) {
            this(key, List.of(), () -> {});
        }

        Backed(TagKey<T> key, Iterable<RegistryKey<T>> entries, Runnable onChange) {
            this.reference = new Reference<>(key);
            entries.forEach(this.entries::add);
            this.onChange = onChange;
        }

        TagKey<T> key() {
            return reference.key();
        }

        RegistryTag<T> reference() {
            return reference;
        }

        Set<RegistryKey<T>> entries() {
            return entriesView;
        }

        public int size() {
            return entries.size();
        }

        @Override
        public Iterator<RegistryKey<T>> iterator() {
            return entries.iterator();
        }

        @ApiStatus.Internal
        void add(RegistryKey<T> key) {
            if (entries.add(key))
                onChange.run();
        }

        @ApiStatus.Internal
        void remove(RegistryKey<T> key) {
            if (entries.remove(key))
                onChange.run();
        }
    }

    record Direct<T>(List<RegistryKey<T>> keys) implements RegistryTag<T> {
        public Direct {
            keys = keys.stream().<RegistryKey<T>>map(key -> new RegistryKeyImpl<T>(key.key())).toList();
        }

        @Override
        public @Nullable TagKey<T> key() {
            return null;
        }
    }

}
