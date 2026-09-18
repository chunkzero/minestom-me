package net.minestom.server.tag;

import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.UnknownNullability;

/**
 * Represents an element which can read {@link Tag tags}.
 */
public interface TagReadable {

    /** Registry context of a bound accessor; unbound access fails explicitly. */
    default Registries tagRegistries() {
        throw new IllegalStateException("Contextual tags require explicit Registries or an accessor bound with withRegistries");
    }

    /** Returns an accessor sharing this data and retaining only the supplied registry context. */
    default TagReadable withRegistries(Registries registries) {
        return new TagAccessors.Readable(this, registries);
    }

    default <T> @UnknownNullability T getTag(ContextualTag<T> tag) {
        return getTag(tag, tagRegistries());
    }

    default <T> @UnknownNullability T getTag(ContextualTag<T> tag, Registries registries) {
        var impl = (ContextualTagImpl<T>) tag;
        return impl.decode(getTag(impl.storage()), registries);
    }

    default boolean hasTag(ContextualTag<?> tag) {
        return getTag(tag) != null;
    }

    default boolean hasTag(ContextualTag<?> tag, Registries registries) {
        return getTag(tag, registries) != null;
    }

    /**
     * Reads the specified tag.
     *
     * @param tag the tag to read
     * @param <T> the tag type
     * @return the read tag, null if not present
     */
    <T> @UnknownNullability T getTag(Tag<T> tag);

    /**
     * Returns if a tag is present.
     *
     * @param tag the tag to check
     * @return true if the tag is present, false otherwise
     */
    default boolean hasTag(Tag<?> tag) {
        return getTag(tag) != null;
    }
}
