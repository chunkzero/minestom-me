package net.minestom.server.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;

/**
 * Serializes reusable values using registries supplied at the conversion boundary.
 *
 * @param <T> the type to serialize
 */
public interface ContextualTagSerializer<T> {
    /** The reader is bound to the supplied registries, including for nested contextual tags. */
    @Nullable T read(TagReadable reader, Registries registries);

    /** The writer is bound to the supplied registries, including for nested contextual tags. */
    void write(TagWritable writer, T value, Registries registries);

    static <T> ContextualTagSerializer<T> fromCompound(
            BiFunction<CompoundBinaryTag, Registries, T> reader,
            BiFunction<T, Registries, CompoundBinaryTag> writer) {
        return new ContextualTagSerializer<>() {
            @Override
            public @Nullable T read(TagReadable readable, Registries registries) {
                return reader.apply(TagSerializer.COMPOUND.read(readable), registries);
            }

            @Override
            public void write(TagWritable writable, T value, Registries registries) {
                TagSerializer.COMPOUND.write(writable, writer.apply(value, registries));
            }
        };
    }
}
