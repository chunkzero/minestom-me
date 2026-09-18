package net.minestom.server.tag;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.CustomData;
import net.minestom.server.item.component.TypedCustomData;
import net.minestom.server.item.instrument.Instrument;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessContextualTagTest {
    private static final Key SHARED = Key.key("test:shared");
    private static final Tag<Integer> SCORE = Tag.Integer("score");
    private static final ContextualTag<ItemStack> REWARD = Tag.ItemStack("reward");
    private static final ContextualTag<Component> TITLE = Tag.Component("title");
    private static final ContextualTagSerializer<DimensionType> DIMENSION_SERIALIZER = ContextualTagSerializer.fromCompound(
            (nbt, registries) -> registries.dimensionType().get(Key.key(nbt.getString("key"))),
            (value, registries) -> CompoundBinaryTag.builder().putString("key",
                    Objects.requireNonNull(registries.dimensionType().getKey(value)).key().asString()).build());
    private static final ContextualTag<DimensionType> DIMENSION = ContextualTag.Structure("dimension", DIMENSION_SERIALIZER);

    @Test
    void sharedStorageCopiesAndCachesUseTheReadingContext() {
        try (var pair = configuredPair()) {
            var a = pair.first().registries();
            var b = pair.second().registries();
            var handler = TagHandler.newHandler();
            var first = handler.withRegistries(a);
            var second = first.withRegistries(b);
            first.setTag(DIMENSION, dimension(a));
            first.setTag(SCORE, 7);
            var encoded = first.asCompound();
            assertSame(encoded, second.asCompound());
            assertSame(dimension(a), first.getTag(DIMENSION));
            assertSame(dimension(b), second.getTag(DIMENSION));
            assertSame(dimension(a), first.getTag(DIMENSION));
            assertEquals(7, second.getTag(SCORE));
            assertSame(dimension(b), first.copy().withRegistries(b).getTag(DIMENSION));
            var snapshot = first.readableCopy();
            assertSame(dimension(a), snapshot.getTag(DIMENSION));
            assertSame(dimension(b), snapshot.withRegistries(b).getTag(DIMENSION));
            assertEquals(encoded, TagSerializer.COMPOUND.read(snapshot));
            first.updateContent(CompoundBinaryTag.empty());
            assertNull(first.readableCopy().getTag(DIMENSION));
            assertSame(dimension(a), snapshot.getTag(DIMENSION));
            assertNull(second.getTag(DIMENSION));

            var lazy = Tag.Structure(DIMENSION.key(), TagSerializer.fromCompound(
                    nbt -> nbt.getString("key"),
                    key -> CompoundBinaryTag.builder().putString("key", key).build()));
            handler.setTag(lazy, SHARED.asString());
            assertSame(dimension(b), second.getTag(DIMENSION));
            assertSame(dimension(a), first.getTag(DIMENSION));
            assertSame(dimension(b), first.readableCopy().withRegistries(b).getTag(DIMENSION));
        }
    }

    @Test
    void itemConversionsDoNotReuseAnEarlierRegistryLookup() {
        try (var pair = configuredPair()) {
            var a = pair.first().registries();
            var b = pair.second().registries();
            var firstItem = exclusiveItem(a, "test:first", Instrument.PONDER_GOAT_HORN);
            var secondItem = exclusiveItem(b, "test:second", Instrument.SING_GOAT_HORN);
            var handler = TagHandler.newHandler();
            handler.setTag(REWARD, firstItem, a);
            assertEquals(firstItem, handler.getTag(REWARD, a));
            assertThrows(IllegalArgumentException.class, () -> handler.getTag(REWARD, b));
            assertThrows(IllegalArgumentException.class, () -> handler.copy().getTag(REWARD, b));
            assertThrows(IllegalArgumentException.class, () -> handler.readableCopy().getTag(REWARD, b));
            handler.setTag(REWARD, secondItem, b);
            assertEquals(secondItem, handler.getTag(REWARD, b));
            assertThrows(IllegalArgumentException.class, () -> handler.getTag(REWARD, a));
            assertEquals(secondItem, TagHandler.fromCompound(handler.asCompound(), b).getTag(REWARD));
            var list = REWARD.list().path("nested");
            handler.setTag(list, List.of(secondItem), b);
            assertEquals(List.of(secondItem), handler.getTag(list, b));
            assertThrows(IllegalArgumentException.class, () -> handler.getTag(list, a));
            pair.first().close();
            assertEquals(secondItem, handler.getAndUpdateTag(REWARD, value -> value.withAmount(2), b));
            assertEquals(secondItem.withAmount(2), handler.getTag(REWARD, b));
        }
    }

    @Test
    void ownedEntityPlayerAndInventoryAccessSuppliesRegistries() {
        try (var pair = configuredPair()) {
            var first = new Entity(pair.first(), EntityType.ZOMBIE);
            var second = new Entity(pair.second(), EntityType.ZOMBIE);
            var inventory = new Inventory(pair.second(), InventoryType.HOPPER, "tags");
            var connection = new PlayerConnection(pair.second()) {
                @Override
                public void sendPacket(SendablePacket packet) {
                }

                @Override
                public SocketAddress getRemoteAddress() {
                    return new InetSocketAddress("localhost", 0);
                }
            };
            var player = new Player(connection, new GameProfile(UUID.randomUUID(), "tags"));
            first.setTag(DIMENSION, dimension(pair.first().registries()));
            for (Taggable owned : List.of(second, player, inventory, player.getInventory())) {
                owned.tagHandler().updateContent(first.tagHandler().asCompound());
                assertSame(dimension(pair.second().registries()), owned.getTag(DIMENSION));
                owned.setTag(TITLE, Component.text("owned"));
                assertEquals(Component.text("owned"), owned.getTag(TITLE));
                assertSame(pair.second().registries(), owned.tagRegistries());
            }
            pair.first().close();
            assertEquals(Component.text("owned"), player.getAndSetTag(TITLE, Component.text("still running")));
            assertEquals(Component.text("still running"), player.getTag(TITLE));
        }
    }

    @Test
    void standaloneValuesAndNbtRequireExplicitContext() {
        try (var pair = configuredPair()) {
            var a = pair.first().registries();
            var b = pair.second().registries();
            var tag = DIMENSION.path("nested", "data");
            var item = ItemStack.of(Material.DIAMOND).withTag(tag, dimension(a), a);
            var block = Block.CHEST.withTag(tag, dimension(a), a);
            var customData = CustomData.EMPTY.withTag(tag, dimension(a), a);
            var typedData = new TypedCustomData<>("type", CompoundBinaryTag.empty()).withTag(tag, dimension(a), a);
            var built = ItemStack.builder(Material.DIAMOND).set(tag, dimension(a), a).build();
            for (TagReadable value : List.of(item, block, customData, typedData, built)) {
                assertThrows(IllegalStateException.class, () -> value.getTag(tag));
                assertSame(dimension(a), value.getTag(tag, a));
                assertSame(dimension(b), value.withRegistries(b).getTag(tag));
            }
            var nbt = CompoundBinaryTag.builder().putInt("untouched", 12);
            tag.write(nbt, dimension(a), a);
            assertSame(dimension(b), tag.read(nbt.build(), b));
            tag.write(nbt, null, b);
            assertNull(tag.read(nbt.build(), a));
            assertEquals(12, nbt.build().getInt("untouched"));
        }
    }

    @Test
    void missingContextFailsEvenForAbsentDefaultedOrRemovedValues() {
        var handler = TagHandler.newHandler();
        var fallback = REWARD.defaultValue(ItemStack.AIR);
        assertThrows(IllegalStateException.class, () -> handler.getTag(fallback));
        assertThrows(IllegalStateException.class, () -> handler.hasTag(REWARD));
        assertThrows(IllegalStateException.class, () -> handler.setTag(REWARD, ItemStack.AIR));
        assertThrows(IllegalStateException.class, () -> handler.removeTag(REWARD));
        assertThrows(IllegalStateException.class, () -> handler.getAndSetTag(REWARD, null));
        assertThrows(IllegalStateException.class, () -> handler.updateAndGetTag(REWARD, _ -> ItemStack.AIR));
        assertThrows(IllegalStateException.class, () -> handler.getAndUpdateTag(REWARD, _ -> ItemStack.AIR));
        assertThrows(IllegalStateException.class, () -> handler.updateTag(REWARD, _ -> ItemStack.AIR));
        assertTrue(handler.asCompound().isEmpty());
        handler.setTag(SCORE, 10);
        assertEquals(11, handler.updateAndGetTag(SCORE, value -> value + 1));
    }

    @Test
    void nestedListsMappingsStructuresAndViewsCarryContext() {
        try (var pair = configuredPair()) {
            var a = pair.first().registries();
            var b = pair.second().registries();
            var mapped = DIMENSION.map(DimensionBox::new, DimensionBox::value).list().list().path("nested");
            var handler = TagHandler.newHandler(a);
            handler.setTag(mapped, List.of(List.of(new DimensionBox(dimension(a)))));
            assertEquals(List.of(List.of(new DimensionBox(dimension(b)))), handler.getTag(mapped, b));
            assertEquals(List.of(List.of(new DimensionBox(dimension(b)))), handler.copy().withRegistries(b).getTag(mapped));
            var structure = ContextualTag.Structure("outer", new ContextualTagSerializer<DimensionBox>() {
                @Override
                public DimensionBox read(TagReadable reader, Registries registries) {
                    assertSame(registries, reader.tagRegistries());
                    return new DimensionBox(reader.getTag(DIMENSION));
                }

                @Override
                public void write(TagWritable writer, DimensionBox value, Registries registries) {
                    assertSame(registries, writer.tagRegistries());
                    writer.setTag(DIMENSION, value.value());
                }
            });
            handler.setTag(structure, new DimensionBox(dimension(a)));
            assertEquals(new DimensionBox(dimension(b)), handler.getTag(structure, b));
            var view = ContextualTag.View(DIMENSION_SERIALIZER).path("view");
            handler.setTag(view, dimension(a));
            assertSame(dimension(b), handler.getTag(view, b));
            assertSame(dimension(b), handler.getAndUpdateTag(view, value -> value, b));
            assertNull(handler.updateAndGetTag(view, _ -> null, b));
            assertNull(handler.getTag(view, b));
            assertEquals(new DimensionBox(dimension(b)), handler.getTag(structure, b));
        }
    }

    @Test
    void updatesConvertTheExistingRepresentationAndInvalidateCopies() {
        try (var pair = configuredPair()) {
            var a = pair.first().registries();
            var b = pair.second().registries();
            var handler = TagHandler.newHandler(a);
            handler.setTag(DIMENSION, dimension(a));
            var snapshot = handler.readableCopy();
            assertSame(dimension(b), handler.updateAndGetTag(DIMENSION, value -> {
                assertSame(dimension(b), value);
                return value;
            }, b));
            assertSame(dimension(b), handler.getAndSetTag(DIMENSION, null, b));
            assertNull(handler.readableCopy().getTag(DIMENSION));
            assertSame(dimension(a), snapshot.getTag(DIMENSION));
            var fallbackCalls = new AtomicInteger();
            var tag = TITLE.defaultValue(() -> Component.text("default " + fallbackCalls.incrementAndGet()));
            assertNull(handler.updateAndGetTag(tag, _ -> null));
            assertEquals(1, fallbackCalls.get());
            handler.setTag(TITLE, Component.text("before"));
            handler.updateTag(TITLE, value -> value.append(Component.text(" after")));
            assertEquals(Component.text("before").append(Component.text(" after")), handler.getTag(TITLE));
            handler.setTag(Tag.String("title"), "raw");
            assertEquals(Component.text("raw"), handler.getAndUpdateTag(TITLE, _ -> Component.text("converted")));
            assertEquals(Component.text("converted"), handler.getTag(TITLE));
        }
    }

    @Test
    void reflectiveRecordsAreExplicitAndReusableAcrossContexts() {
        assertThrows(IllegalArgumentException.class, () -> Tag.Structure("reward", Reward.class));
        assertThrows(IllegalArgumentException.class, () -> Tag.View(Envelope.class));
        assertThrows(IllegalArgumentException.class, () -> Tag.Structure("recursive", Recursive.class));
        var tag = ContextualTag.Structure("envelope", Envelope.class);
        var view = ContextualTag.View(Envelope.class);
        try (var pair = configuredPair()) {
            var a = pair.first().registries();
            var b = pair.second().registries();
            var item = exclusiveItem(a, "test:record", Instrument.PONDER_GOAT_HORN);
            var value = new Envelope(new Reward(item, Component.text("reward")), new Vec(1, 2, 3));
            var handler = TagHandler.newHandler(a);
            handler.setTag(tag, value);
            assertEquals(value, handler.getTag(ContextualTag.Structure("envelope", Envelope.class)));
            assertThrows(IllegalArgumentException.class, () -> handler.getTag(tag, b));
            b.instrument().register("test:record", b.instrument().get(Instrument.SING_GOAT_HORN));
            assertEquals(value, handler.copy().getTag(tag, b));
            assertEquals(value, TagHandler.fromCompound(handler.asCompound(), b).getTag(tag));
            var list = tag.list().path("nested");
            handler.setTag(list, List.of(value), a);
            assertEquals(List.of(value), handler.readableCopy().getTag(list, b));
            handler.setTag(view, value, b);
            assertEquals(value, handler.readableCopy().getTag(view));
            var serializer = TagRecord.contextualSerializer(Envelope.class);
            assertEquals(value, serializer.read(handler.readableCopy(), b));
            pair.first().close();
            assertEquals(value, handler.withRegistries(b).getAndUpdateTag(view, previous -> previous));
        }
    }

    private static ServerProcessPair configuredPair() {
        var pair = new ServerProcessPair();
        pair.first().registries().dimensionType().register(SHARED, DimensionType.builder().ambientLight(0.1f).build());
        pair.second().registries().dimensionType().register("test:padding", DimensionType.builder().ambientLight(0.5f).build());
        pair.second().registries().dimensionType().register(SHARED, DimensionType.builder().ambientLight(0.9f).build());
        assertNotEquals(dimension(pair.first().registries()), dimension(pair.second().registries()));
        return pair;
    }

    private static DimensionType dimension(Registries registries) {
        return Objects.requireNonNull(registries.dimensionType().get(SHARED));
    }

    private static ItemStack exclusiveItem(Registries registries, String key,
                                            RegistryKey<Instrument> source) {
        var registered = registries.instrument().register(key, registries.instrument().get(source));
        return ItemStack.of(Material.GOAT_HORN).with(DataComponents.INSTRUMENT, registered);
    }

    record DimensionBox(DimensionType value) {
    }

    record Reward(ItemStack item, Component title) {
    }

    record Envelope(Reward reward, Vec position) {
    }

    record Recursive(Recursive nested) {
    }
}
