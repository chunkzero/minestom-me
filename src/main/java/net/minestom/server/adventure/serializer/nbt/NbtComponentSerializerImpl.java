package net.minestom.server.adventure.serializer.nbt;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryTranscoder;

final class NbtComponentSerializerImpl implements NbtComponentSerializer {
    private final Transcoder<BinaryTag> coder;

    NbtComponentSerializerImpl(Registries registries) {
        this.coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
    }

    @Override
    public Component deserialize(BinaryTag input) {
        return Codec.COMPONENT.decode(coder, input).orElseThrow();
    }

    @Override
    public BinaryTag serialize(Component component) {
        return Codec.COMPONENT.encode(coder, component).orElseThrow();
    }

}
