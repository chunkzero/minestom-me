package net.minestom.server.adventure;

import com.google.gson.JsonNull;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.serializer.gson.GsonDataComponentValue;
import net.minestom.server.adventure.serializer.nbt.NbtDataComponentValue;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.component.DataComponent;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryTranscoder;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public sealed interface MinestomDataComponentValue extends DataComponentValue permits MinestomDataComponentValueImpl, MinestomDataComponentValueImpl.Removed {

    static MinestomDataComponentValue removed() {
        return MinestomDataComponentValueImpl.Removed.INSTANCE;
    }

    static MinestomDataComponentValue dataComponentValue(final @Nullable Object data) {
        return new MinestomDataComponentValueImpl(data);
    }

    /** Decodes an Adventure component value using the registries at the consuming boundary. */
    static MinestomDataComponentValue from(Key key, DataComponentValue value, Registries registries) {
        Objects.requireNonNull(registries, "Data component conversion requires Registries");
        if (value instanceof Removed) return removed();
        var component = component(key);
        return switch (value) {
            case MinestomDataComponentValue nativeValue -> nativeValue;
            case GsonDataComponentValue json -> dataComponentValue(component.decode(
                    new RegistryTranscoder<>(Transcoder.JSON, registries), json.element()).orElseThrow());
            case NbtDataComponentValue nbt -> dataComponentValue(component.decode(
                    new RegistryTranscoder<>(Transcoder.NBT, registries), nbt.value()).orElseThrow());
            default -> throw new IllegalArgumentException("Unsupported data component value: " + value.getClass());
        };
    }

    /** Encodes a value for use with an Adventure Gson serializer, without retaining registries. */
    default GsonDataComponentValue toGson(Key key, Registries registries) {
        Objects.requireNonNull(registries, "Data component conversion requires Registries");
        return GsonDataComponentValue.gsonDataComponentValue(this instanceof Removed ? JsonNull.INSTANCE :
                component(key).encode(new RegistryTranscoder<>(Transcoder.JSON, registries), value()).orElseThrow());
    }

    /** Encodes a value as NBT, without retaining registries. */
    default NbtDataComponentValue toNbt(Key key, Registries registries) {
        Objects.requireNonNull(registries, "Data component conversion requires Registries");
        return this instanceof Removed ? NbtDataComponentValue.removed() : NbtDataComponentValue.nbtDataComponentValue(
                component(key).encode(new RegistryTranscoder<>(Transcoder.NBT, registries), value()).orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static DataComponent<Object> component(Key key) {
        var component = DataComponent.fromKey(key);
        if (component == null) throw new IllegalArgumentException("Unknown data component: " + key);
        return (DataComponent<Object>) component;
    }

    @Nullable Object value();
}
