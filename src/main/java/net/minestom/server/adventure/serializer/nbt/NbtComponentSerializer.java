package net.minestom.server.adventure.serializer.nbt;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import net.minestom.server.registry.Registries;

public sealed interface NbtComponentSerializer extends ComponentSerializer<Component, Component, BinaryTag> permits NbtComponentSerializerImpl {
    /** Creates a component serializer bound to the supplied registries. */
    static NbtComponentSerializer nbt(Registries registries) {
        return new NbtComponentSerializerImpl(registries);
    }
}
