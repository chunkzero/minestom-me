package net.minestom.server.tag;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.property.ServerProperties;
import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

record ContextualTagImpl<T>(Tag<BinaryTag> storage,
                            BiFunction<BinaryTag, Registries, @Nullable T> reader,
                            BiFunction<T, Registries, BinaryTag> writer,
                            @Nullable Supplier<T> defaultValue) implements ContextualTag<T> {
    static <T> ContextualTag<T> create(String key,
                                     BiFunction<BinaryTag, Registries, @Nullable T> reader,
                                     BiFunction<T, Registries, BinaryTag> writer) {
        return new ContextualTagImpl<>(TagImpl.preservedNbt(key), reader, writer, null);
    }

    static <T> ContextualTag<T> structure(String key, ContextualTagSerializer<T> serializer) {
        return create(key, (nbt, registries) -> {
            if (!(nbt instanceof CompoundBinaryTag compound)) return null;
            if (!ServerProperties.SERIALIZE_EMPTY_COMPOUND.get() && compound.isEmpty()) return null;
            return serializer.read(TagHandler.fromCompound(compound, registries), registries);
        }, (value, registries) -> {
            var handler = TagHandler.newHandler(registries);
            serializer.write(handler, value, registries);
            return handler.asCompound();
        });
    }

    @Override
    public Tag<BinaryTag> asNbt() {
        return storage;
    }

    @Override
    public String key() {
        return storage.key();
    }

    @Override
    public ContextualTag<T> defaultValue(Supplier<T> defaultValue) {
        return new ContextualTagImpl<>(storage, reader, writer, Objects.requireNonNull(defaultValue));
    }

    @Override
    public ContextualTag<T> defaultValue(T defaultValue) {
        return defaultValue(() -> defaultValue);
    }

    @Override
    public <R> ContextualTag<R> map(Function<T, R> readMap, Function<R, T> writeMap) {
        return new ContextualTagImpl<>(storage, (nbt, registries) -> {
            T value = reader.apply(nbt, registries);
            return value == null ? null : readMap.apply(value);
        }, (value, registries) -> writer.apply(writeMap.apply(value), registries), () -> {
            T value = createDefault();
            return value == null ? null : readMap.apply(value);
        });
    }

    @Override
    public ContextualTag<List<T>> list() {
        return new ContextualTagImpl<>(storage, (nbt, registries) -> {
            if (!(nbt instanceof ListBinaryTag list)) return null;
            return list.stream().map(element -> reader.apply(element, registries)).toList();
        }, (values, registries) -> {
            var encoded = values.stream().map(value -> writer.apply(value, registries)).toList();
            return encoded.isEmpty() ? ListBinaryTag.empty() : ListBinaryTag.listBinaryTag(encoded.getFirst().type(), encoded);
        }, null);
    }

    @Override
    public ContextualTag<T> path(String @Nullable ... path) {
        return new ContextualTagImpl<>(storage.path(path), reader, writer, defaultValue);
    }

    @Override
    public T read(CompoundBinaryTag nbt, Registries registries) {
        return decode(storage.read(nbt), registries);
    }

    @Override
    public void write(CompoundBinaryTag.Builder nbt, @Nullable T value, Registries registries) {
        TagHandlerImpl.writeThroughHandler(nbt, handler -> handler.setTag(this, value, registries));
    }

    @Override
    public boolean isView() {
        return storage.isView();
    }

    @Override
    public T createDefault() {
        return defaultValue == null ? null : defaultValue.get();
    }

    T decode(@Nullable BinaryTag nbt, Registries registries) {
        Objects.requireNonNull(registries, "Contextual tags require Registries");
        T value = nbt == null ? null : reader.apply(nbt, registries);
        return value == null ? createDefault() : value;
    }

    @Nullable BinaryTag encode(@Nullable T value, Registries registries) {
        Objects.requireNonNull(registries, "Contextual tags require Registries");
        return value == null ? null : Objects.requireNonNull(writer.apply(value, registries), "Unable to serialize contextual tag " + key());
    }
}
