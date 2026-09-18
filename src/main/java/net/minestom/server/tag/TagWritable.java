package net.minestom.server.tag;

import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Represents an element which can write {@link Tag tags}.
 */
public interface TagWritable extends TagReadable {

    @Override
    default TagWritable withRegistries(Registries registries) {
        return new TagAccessors.Writable(this, registries);
    }

    default <T> void setTag(ContextualTag<T> tag, @Nullable T value) {
        setTag(tag, value, tagRegistries());
    }

    default <T> void setTag(ContextualTag<T> tag, @Nullable T value, Registries registries) {
        var impl = (ContextualTagImpl<T>) tag;
        setTag(impl.storage(), impl.encode(value, registries));
    }

    default void removeTag(ContextualTag<?> tag) {
        setTag(tag, null);
    }

    default void removeTag(ContextualTag<?> tag, Registries registries) {
        setTag(tag, null, registries);
    }

    default <T> @Nullable T getAndSetTag(ContextualTag<T> tag, @Nullable T value) {
        return getAndSetTag(tag, value, tagRegistries());
    }

    default <T> @Nullable T getAndSetTag(ContextualTag<T> tag, @Nullable T value, Registries registries) {
        return getAndUpdateTag(tag, _ -> value, registries);
    }

    default <T> void updateTag(ContextualTag<T> tag, UnaryOperator<@UnknownNullability T> value) {
        updateTag(tag, value, tagRegistries());
    }

    default <T> void updateTag(ContextualTag<T> tag, UnaryOperator<@UnknownNullability T> value, Registries registries) {
        Objects.requireNonNull(registries, "Contextual tags require Registries");
        var impl = (ContextualTagImpl<T>) tag;
        updateTag(impl.storage(), nbt -> impl.encode(value.apply(impl.decode(nbt, registries)), registries));
    }

    default <T> @UnknownNullability T updateAndGetTag(ContextualTag<T> tag, UnaryOperator<@UnknownNullability T> value) {
        return updateAndGetTag(tag, value, tagRegistries());
    }

    default <T> @UnknownNullability T updateAndGetTag(ContextualTag<T> tag, UnaryOperator<@UnknownNullability T> value, Registries registries) {
        return updateContextualTag(tag, value, registries, false);
    }

    default <T> @UnknownNullability T getAndUpdateTag(ContextualTag<T> tag, UnaryOperator<@UnknownNullability T> value) {
        return getAndUpdateTag(tag, value, tagRegistries());
    }

    default <T> @UnknownNullability T getAndUpdateTag(ContextualTag<T> tag, UnaryOperator<@UnknownNullability T> value, Registries registries) {
        return updateContextualTag(tag, value, registries, true);
    }

    private <T> @UnknownNullability T updateContextualTag(ContextualTag<T> tag, UnaryOperator<T> value,
                                                        Registries registries, boolean returnPrevious) {
        Objects.requireNonNull(registries, "Contextual tags require Registries");
        var impl = (ContextualTagImpl<T>) tag;
        var result = new AtomicReference<T>();
        updateTag(impl.storage(), nbt -> {
            T previous = impl.decode(nbt, registries);
            T next = value.apply(previous);
            result.set(returnPrevious ? previous : next);
            return impl.encode(next, registries);
        });
        return result.get();
    }

    /**
     * Writes the specified type.
     *
     * @param tag   the tag to write
     * @param value the tag value, null to remove
     * @param <T>   the tag type
     */
    <T> void setTag(Tag<T> tag, @Nullable T value);

    default void removeTag(Tag<?> tag) {
        setTag(tag, null);
    }

    /**
     * Reads the current value, and then write the new one.
     *
     * @param tag   the tag to write
     * @param value the tag value, null to remove
     * @param <T>   the tag type
     * @return the previous tag value, null if not present
     */
    <T> @Nullable T getAndSetTag(Tag<T> tag, @Nullable T value);

    <T> void updateTag(Tag<T> tag,
                       UnaryOperator<@UnknownNullability T> value);

    <T> @UnknownNullability T updateAndGetTag(Tag<T> tag,
                                              UnaryOperator<@UnknownNullability T> value);

    <T> @UnknownNullability T getAndUpdateTag(Tag<T> tag,
                                              UnaryOperator<@UnknownNullability T> value);
}
