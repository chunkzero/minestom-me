package net.minestom.server.tag;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagType;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.LongBinaryTag;
import net.kyori.adventure.nbt.ShortBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.item.ItemStack;
import net.minestom.server.property.ServerProperties;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryTranscoder;
import net.minestom.server.utils.UUIDUtils;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Basic serializers for {@link Tag tags}.
 */
final class Serializers {
    static final Entry<Byte, ByteBinaryTag> BYTE = new Entry<>(BinaryTagTypes.BYTE, ByteBinaryTag::value, ByteBinaryTag::byteBinaryTag);
    static final Entry<Boolean, ByteBinaryTag> BOOLEAN = new Entry<>(BinaryTagTypes.BYTE, b -> b.value() != 0, b -> b ? ByteBinaryTag.ONE : ByteBinaryTag.ZERO);
    static final Entry<Short, ShortBinaryTag> SHORT = new Entry<>(BinaryTagTypes.SHORT, ShortBinaryTag::value, ShortBinaryTag::shortBinaryTag);
    static final Entry<Integer, IntBinaryTag> INT = new Entry<>(BinaryTagTypes.INT, IntBinaryTag::value, IntBinaryTag::intBinaryTag);
    static final Entry<Long, LongBinaryTag> LONG = new Entry<>(BinaryTagTypes.LONG, LongBinaryTag::value, LongBinaryTag::longBinaryTag);
    static final Entry<Float, FloatBinaryTag> FLOAT = new Entry<>(BinaryTagTypes.FLOAT, FloatBinaryTag::value, FloatBinaryTag::floatBinaryTag);
    static final Entry<Double, DoubleBinaryTag> DOUBLE = new Entry<>(BinaryTagTypes.DOUBLE, DoubleBinaryTag::value, DoubleBinaryTag::doubleBinaryTag);
    static final Entry<String, StringBinaryTag> STRING = new Entry<>(BinaryTagTypes.STRING, StringBinaryTag::value, StringBinaryTag::stringBinaryTag);
    static final Entry<BinaryTag, BinaryTag> NBT_ENTRY = new Entry<>(null, Function.identity(), Function.identity());
    static final Entry<BinaryTag, BinaryTag> PRESERVED_NBT_ENTRY = new Entry<>(null, Function.identity(), Function.identity(), false, true);

    static final Entry<java.util.UUID, IntArrayBinaryTag> UUID = new Entry<>(BinaryTagTypes.INT_ARRAY, UUIDUtils::fromNbt, UUIDUtils::toNbt);
    static final BiFunction<BinaryTag, Registries, ItemStack> ITEM_READER = (input, registries) ->
            input instanceof CompoundBinaryTag compound ? ItemStack.fromItemNBT(compound, registries) : null;
    static final BiFunction<ItemStack, Registries, BinaryTag> ITEM_WRITER = ItemStack::toItemNBT;
    static final BiFunction<BinaryTag, Registries, Component> COMPONENT_READER = (input, registries) ->
            Codec.COMPONENT.decode(new RegistryTranscoder<>(Transcoder.NBT, registries), input).orElse(null);
    static final BiFunction<Component, Registries, BinaryTag> COMPONENT_WRITER = (component, registries) ->
            Codec.COMPONENT.encode(new RegistryTranscoder<>(Transcoder.NBT, registries), component).orElseThrow();

    static final Entry<Object, ByteBinaryTag> EMPTY = new Entry<>(BinaryTagTypes.BYTE, _ -> null, _ -> null);

    static <T> Entry<T, CompoundBinaryTag> fromTagSerializer(TagSerializer<T> serializer) {
        return new Serializers.Entry<>(BinaryTagTypes.COMPOUND,
                (CompoundBinaryTag compound) -> {
                    if (!ServerProperties.SERIALIZE_EMPTY_COMPOUND.get() && compound.isEmpty()) return null;
                    return serializer.read(TagHandler.fromCompound(compound));
                },
                (value) -> {
                    if (value == null) return CompoundBinaryTag.empty();
                    TagHandler handler = TagHandler.newHandler();
                    serializer.write(handler, value);
                    return handler.asCompound();
                });
    }

    record Entry<T, N extends BinaryTag>(@Nullable BinaryTagType<N> nbtType,
                                         Function<N, @Nullable T> reader,
                                         Function<T, @Nullable N> writer,
                                         boolean isPath, boolean preserveNbt) {
        Entry(@Nullable BinaryTagType<N> nbtType, Function<N, T> reader, Function<T, N> writer) {
            this(nbtType, reader, writer, false, false);
        }

        Entry(@Nullable BinaryTagType<N> nbtType, Function<N, T> reader, Function<T, N> writer, boolean isPath) {
            this(nbtType, reader, writer, isPath, false);
        }

        @Nullable T read(N nbt) {
            return reader.apply(nbt);
        }

        @Nullable N write(T value) {
            return writer.apply(value);
        }
    }
}
