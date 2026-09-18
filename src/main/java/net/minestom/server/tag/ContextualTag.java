package net.minestom.server.tag;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A reusable tag definition whose conversion requires registries. Definitions retain no registry context.
 * Handlers encode contextual values when written and decode them when read. Only their NBT representation
 * is cached, so copies and accessors bound to different registries never share decoded values.
 * Repeated reads perform conversion each time. For values read every tick, retain the decoded value
 * within the owning registry context and refresh it when the tag or relevant registries change.
 * Use an owner's bound handler or pass registries explicitly for standalone data:
 * <pre>{@code
 * static final ContextualTag<ItemStack> REWARD = Tag.ItemStack("reward");
 * player.setTag(REWARD, reward);
 * var item = ItemStack.of(Material.CHEST).withTag(REWARD, reward, registries);
 * var stored = item.getTag(REWARD, registries);
 * var accessor = handler.withRegistries(registries);
 * }</pre>
 *
 * @param <T> the tag value type
 */
public sealed interface ContextualTag<T extends @UnknownNullability Object> permits ContextualTagImpl {
    static <T> ContextualTag<T> Structure(String key, ContextualTagSerializer<T> serializer) {
        return ContextualTagImpl.structure(key, serializer);
    }

    static <T> ContextualTag<T> View(ContextualTagSerializer<T> serializer) {
        return Structure("", serializer);
    }

    /** Supports ordinary and contextual fields, including nested records. */
    @ApiStatus.Experimental
    static <T extends Record> ContextualTag<T> Structure(String key, Class<T> type) {
        return Structure(key, TagRecord.contextualSerializer(type));
    }

    @ApiStatus.Experimental
    static <T extends Record> ContextualTag<T> View(Class<T> type) {
        return View(TagRecord.contextualSerializer(type));
    }

    String key();

    /** Selects the serialized representation, for example when exposing block entity NBT to clients. */
    Tag<BinaryTag> asNbt();

    ContextualTag<T> defaultValue(Supplier<T> defaultValue);

    ContextualTag<T> defaultValue(T defaultValue);

    <R> ContextualTag<R> map(Function<T, R> readMap, Function<R, T> writeMap);

    ContextualTag<List<T>> list();

    ContextualTag<T> path(String @Nullable ... path);

    T read(CompoundBinaryTag nbt, Registries registries);

    void write(CompoundBinaryTag.Builder nbt, @Nullable T value, Registries registries);

    boolean isView();

    T createDefault();
}
