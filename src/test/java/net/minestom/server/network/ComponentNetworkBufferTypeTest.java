package net.minestom.server.network;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.object.ObjectContents;
import net.minestom.server.adventure.MinestomDataComponentValue;
import net.minestom.server.adventure.serializer.nbt.NbtDataComponentValue;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.instrument.Instrument;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryTranscoder;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minestom.server.network.NetworkBuffer.COMPONENT;
import static net.minestom.server.network.NetworkBuffer.NBT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ComponentNetworkBufferTypeTest {
    @Test
    void itemHoverComponentPatchUsesTheBuffersRegistries() {
        try (var pair = new ServerProcessPair()) {
            var a = pair.first().registries();
            var b = pair.second().registries();
            var key = a.instrument().register("test:hover", a.instrument().get(Instrument.PONDER_GOAT_HORN));
            b.instrument().register(key.key(), b.instrument().get(Instrument.SING_GOAT_HORN));
            var item = ItemStack.of(Material.GOAT_HORN)
                    .with(DataComponents.CUSTOM_NAME, Component.text("Named"))
                    .with(DataComponents.INSTRUMENT, key);
            var component = Component.text("hover me").hoverEvent(item.asHoverEvent());
            assertThrows(NullPointerException.class, () -> write(component));
            var first = write(component, a).getCompound("hover_event");
            assertEquals("show_item", first.getString("action"));
            assertEquals("minecraft:goat_horn", first.getString("id"));
            assertEquals(1, first.getInt("count"));
            var patch = first.getCompound("components");
            assertEquals(Component.text("Named"), DataComponents.CUSTOM_NAME.decode(
                    new RegistryTranscoder<>(Transcoder.NBT, a), patch.get("minecraft:custom_name")).orElseThrow());
            assertEquals("test:hover", patch.getString("minecraft:instrument"));
            pair.first().close();
            assertEquals(first, write(component, b).getCompound("hover_event"));
            assertEquals(b.instrument().get(Instrument.SING_GOAT_HORN), DataComponents.INSTRUMENT.decode(
                    new RegistryTranscoder<>(Transcoder.NBT, b), patch.get("minecraft:instrument")).orElseThrow().resolve(b.instrument()));
        }
    }

    @Test
    void itemHoverSupportsMixedNativeAndNbtValuesIncludingRemovals() {
        try (var pair = new ServerProcessPair()) {
            var patch = Map.of(
                    DataComponents.CUSTOM_NAME.key(), MinestomDataComponentValue.dataComponentValue(Component.text("Named")),
                    DataComponents.CUSTOM_DATA.key(), NbtDataComponentValue.nbtDataComponentValue(CompoundBinaryTag.builder().putInt("value", 7).build()),
                    DataComponents.DAMAGE.key(), MinestomDataComponentValue.removed(),
                    DataComponents.LORE.key(), NbtDataComponentValue.removed());
            var component = Component.text("hover me").hoverEvent(HoverEvent.showItem(Material.STONE, 1, patch));
            var written = write(component, pair.first().registries()).getCompound("hover_event").getCompound("components");
            assertEquals(7, written.getCompound("minecraft:custom_data").getInt("value"));
            assertEquals(CompoundBinaryTag.empty(), written.get("!minecraft:damage"));
            assertEquals(CompoundBinaryTag.empty(), written.get("!minecraft:lore"));
        }
    }

    @Test
    void empty() {
        var comp = Component.empty();
        assertWriteReadEquality(comp);
    }

    @Test
    void text() {
        var comp = Component.text("Hello, world!");
        assertWriteReadEquality(comp);
    }

    @Test
    void textChildren() {
        var comp = Component.text("Hello, world!").children(List.of(
                Component.text("child 1"),
                Component.text("child 2")
        ));
        assertWriteReadEquality(comp);
    }

    @Test
    void translatable() {
        var comp = Component.translatable("a.b.c", "I am fallback", Component.text("arg1"), Component.text("arg2"));
        assertWriteReadEquality(comp);
    }

    @Test
    void score() {
        var comp = Component.score("test123", "obj");
        assertWriteReadEquality(comp);
    }

    @Test
    void selector() {
        var comp = Component.selector("@a", Component.text(", "));
        assertWriteReadEquality(comp);
    }

    @Test
    void keybind() {
        var comp = Component.keybind("key.jump");
        assertWriteReadEquality(comp);
    }

    @Test
    void textModifiedUtf8() {
        var comp = Component.text("abc\0\0def");
        assertWriteReadEquality(comp);
    }

    @Test
    void hoverAction() {
        var comp = Component.text("hello").hoverEvent(Component.text("world"));
        assertWriteReadEquality(comp);
    }

    @Test
    void testObjectComponentHeadString() {
        var comp = Component.object(ObjectContents.playerHead("Hello"));
        assertWriteReadEquality(comp);
    }

    @Test
    void testObjectComponentHeadUUID() {
        var comp = Component.object(ObjectContents.playerHead(UUID.randomUUID()));
        assertWriteReadEquality(comp);
    }

    @Test
    void objectComponentHeadTexture() {
        var comp = Component.object(ObjectContents.playerHead()
                .texture(Key.key("red"))
                .build());

        final CompoundBinaryTag player = write(comp).getCompound("player");
        assertEquals("red", player.getString("texture"));
        assertFalse(player.contains("body"));
        assertWriteReadEquality(comp);
    }

    @Test
    void objectComponentFallback() {
        var comp = Component.object()
                .contents(ObjectContents.sprite(Key.key("missing")))
                .fallback(Component.text("Missing"))
                .build();

        final CompoundBinaryTag written = write(comp);
        assertInstanceOf(CompoundBinaryTag.class, written.get("fallback"));
        assertEquals(comp, Codec.COMPONENT.decode(Transcoder.NBT, written).orElseThrow());
    }

    private static void assertWriteReadEquality(Component comp) {
        final CompoundBinaryTag written = write(comp);
        final Component actual = Codec.COMPONENT.decode(Transcoder.NBT, written).orElseThrow();
        assertEquals(comp, actual);
    }

    private static CompoundBinaryTag write(Component comp, Registries registries) {
        var buffer = NetworkBuffer.resizableBuffer(registries);
        buffer.write(COMPONENT, comp);
        return assertInstanceOf(CompoundBinaryTag.class, buffer.read(NBT));
    }

    private static CompoundBinaryTag write(Component comp) {
        var array = NetworkBuffer.makeArray(buffer -> buffer.write(COMPONENT, comp));
        var buffer = NetworkBuffer.wrap(array, 0, array.length);
        return assertInstanceOf(CompoundBinaryTag.class, buffer.read(NBT));
    }
}
