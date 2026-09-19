package net.minestom.server.adventure.provider;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.event.DataComponentValueConverterRegistry;
import net.kyori.adventure.text.serializer.gson.GsonDataComponentValue;
import net.minestom.server.adventure.MinestomDataComponentValue;
import net.minestom.server.adventure.serializer.nbt.NbtDataComponentValue;

import java.util.List;

import static net.kyori.adventure.text.event.DataComponentValueConverterRegistry.Conversion.convert;

@SuppressWarnings("UnstableApiUsage") // we are permitted to provide this
public final class MinestomDataComponentValueConverterProvider implements DataComponentValueConverterRegistry.Provider {

    @Override
    public Key id() {
        return Key.key("minestom", "data_component_value_converter");
    }

    @Override
    public List<DataComponentValueConverterRegistry.Conversion<?, ?>> conversions() {
        return List.of(
                convert(GsonDataComponentValue.class, MinestomDataComponentValue.class, (_, _) -> { throw missingContext(); }),
                convert(MinestomDataComponentValue.class, GsonDataComponentValue.class, (_, _) -> { throw missingContext(); }),
                convert(NbtDataComponentValue.class, MinestomDataComponentValue.class, (_, _) -> { throw missingContext(); }),
                convert(MinestomDataComponentValue.class, NbtDataComponentValue.class, (_, _) -> { throw missingContext(); })
        );
    }

    private static IllegalStateException missingContext() {
        return new IllegalStateException("Data component conversion requires Registries; use MinestomDataComponentValue.from, toGson, or toNbt with explicit registries");
    }
}
