package net.minestom.server.adventure;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.util.Codec;

import java.io.IOException;

/**
 * Adventure related constants, etc.
 */
public final class MinestomAdventure {
    /**
     * See {@link MinestomAdventure#tagStringIO()}
     */
    private static final TagStringIO tagStringIO = TagStringIO.builder()
            .emitHeterogeneousLists(true)
            .acceptHeterogeneousLists(true)
            .build();

    /**
     * A codec to convert between strings and NBT.
     */
    public static final Codec<CompoundBinaryTag, String, IOException, IOException> NBT_CODEC
            = Codec.codec(tagStringIO::asCompound, tagStringIO::asString);

    private MinestomAdventure() {
    }

    /**
     * Gets the {@link TagStringIO} instance used to convert SNBT.
     * This instance should be used for all Adventure related SNBT parsing and serialization.
     * Note: This instance of the {@link TagStringIO} is configured to accept and emit heterogeneous lists
     *
     * @return the tag string IO instance
     */
    public static TagStringIO tagStringIO() {
        return tagStringIO;
    }

    public static BinaryTagHolder wrapNbt(BinaryTag nbt) {
        return new BinaryTagHolderImpl(nbt);
    }

    public static BinaryTag unwrapNbt(BinaryTagHolder holder) {
        if (holder instanceof BinaryTagHolderImpl(BinaryTag nbt))
            return nbt;
        try {
            return holder.get(MinestomAdventure.NBT_CODEC);
        } catch (IOException e) {
            throw new RuntimeException("Failed to unwrap BinaryTagHolder", e);
        }
    }
}
