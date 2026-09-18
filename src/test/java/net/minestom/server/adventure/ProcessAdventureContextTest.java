package net.minestom.server.adventure;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.DataComponentValueConverterRegistry;
import net.kyori.adventure.text.serializer.gson.GsonDataComponentValue;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.instrument.Instrument;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessAdventureContextTest {
    @Test
    void componentConversionsUseExplicitRegistriesWithoutCachingAnotherContext() {
        try (var pair = new ServerProcessPair()) {
            var a = pair.first().registries();
            var b = pair.second().registries();
            var key = a.instrument().register("test:exclusive", a.instrument().get(Instrument.PONDER_GOAT_HORN));
            var value = MinestomDataComponentValue.dataComponentValue(key);
            var component = DataComponents.INSTRUMENT.key();
            var json = value.toGson(component, a);
            var nbt = value.toNbt(component, a);
            assertEquals(value, MinestomDataComponentValue.from(component, json, a));
            assertEquals(value, MinestomDataComponentValue.from(component, nbt, a));
            assertThrows(IllegalArgumentException.class, () -> MinestomDataComponentValue.from(component, json, b));
            assertThrows(IllegalArgumentException.class, () -> MinestomDataComponentValue.from(component, nbt, b));
            b.instrument().register(key.key(), b.instrument().get(Instrument.SING_GOAT_HORN));
            pair.first().close();
            assertEquals(value, MinestomDataComponentValue.from(component, nbt, b));
            assertEquals(json.element(), value.toGson(component, b).element());
            assertEquals(MinestomDataComponentValue.removed(), MinestomDataComponentValue.from(component,
                    MinestomDataComponentValue.removed().toNbt(component, b), b));
            assertThrows(NullPointerException.class, () -> value.toNbt(component, null));
            assertThrows(IllegalStateException.class, () -> DataComponentValueConverterRegistry.convert(
                    GsonDataComponentValue.class, component, value));
            assertThrows(IllegalStateException.class, () -> DataComponentValueConverterRegistry.convert(
                    MinestomDataComponentValue.class, component, nbt));
            assertThrows(IllegalArgumentException.class, () -> value.toNbt(Key.key("test:unknown"), b));
        }
    }

    @Test
    void translationAndFlatteningStayBoundToTheirProcess() {
        try (var pair = new ServerProcessPair()) {
            var a = pair.first().translation();
            var b = pair.second().translation();
            a.setDefaultLocale(Locale.ENGLISH);
            b.setDefaultLocale(Locale.FRENCH);
            a.setTranslator((_, locale) -> Component.text("A:" + locale.getLanguage()));
            b.setTranslator((_, locale) -> Component.text("B:" + locale.getLanguage()));
            var message = Component.translatable("test:key");
            var first = PlainTextComponentSerializer.builder().flattener(a.flattener()).build();
            var second = PlainTextComponentSerializer.builder().flattener(b.flattener()).build();
            assertEquals("A:en", first.serialize(message));
            assertEquals("B:fr", second.serialize(message));
            assertEquals(Component.text("B:de"), b.translate(message, Locale.GERMAN));
            pair.first().close();
            assertEquals("B:fr", second.serialize(message));
            b.setDefaultLocale(Locale.ITALIAN);
            assertEquals("B:it", second.serialize(message));
        }
    }
}
