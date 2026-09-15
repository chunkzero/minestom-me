package net.minestom.server.registry;

import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * A named tag reference or an immutable list of registry keys.
 *
 * <p>Named tags store their key without capturing a registry. Resolve their membership against the
 * registry belonging to the operation, so shared component definitions can be used by multiple servers.</p>
 *
 * <p>Note that all elements of a direct tag must still be members of the registry.</p>
 *
 * @param <T> The type of the registry object.
 */
public sealed interface RegistryTag<T> extends HolderSet<T>
        permits RegistryTagImpl.Empty, RegistryTagImpl.Reference, RegistryTagImpl.Direct {

    static <T> NetworkBuffer.Type<RegistryTag<T>> networkType(Registries.Selector<T> selector) {
        return new RegistryNetworkTypes.RegistryTagImpl<>(selector);
    }

    static <T> Codec<RegistryTag<T>> codec(Registries.Selector<T> selector) {
        return new RegistryCodecs.RegistryTagImpl<>(selector);
    }

    @SuppressWarnings("unchecked")
    static <T> RegistryTag<T> empty() {
        return (RegistryTag<T>) RegistryTagImpl.Empty.INSTANCE;
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    static <T> RegistryTag<T> direct(RegistryKey<T>... keys) {
        if (keys.length == 0) return empty();
        return new RegistryTagImpl.Direct<>(List.of(keys));
    }

    static <T> RegistryTag<T> direct(Collection<? extends RegistryKey<T>> values) {
        if (values.isEmpty()) return empty();
        return new RegistryTagImpl.Direct<>(List.copyOf(values));
    }

    static <T> RegistryTag<T> reference(TagKey<T> key) {
        return new RegistryTagImpl.Reference<>(key);
    }

    @Nullable TagKey<T> key();

    /** Returns read-only membership in the given registry, or an empty collection for a missing tag. */
    default Collection<RegistryKey<T>> resolve(Registry<T> registry) {
        final TagKey<T> key = key();
        if (key != null) {
            return registry.tagValues(key);
        }
        return this instanceof RegistryTagImpl.Direct<T> direct ? direct.keys() : List.of();
    }

    default boolean contains(Registry<T> registry, RegistryKey<T> value) {
        return resolve(registry).contains(new RegistryKeyImpl<>(value.key()));
    }

}
