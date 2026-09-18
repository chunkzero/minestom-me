package net.minestom.server.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.component.DataComponents;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.registry.Registries;
import net.minestom.server.utils.Unit;
import net.minestom.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RegistriesTest
class ContextualTagNbtRegistriesTest {
    private static final ContextualTag<ItemStack> REWARD = Tag.ItemStack("reward");
    private static final ContextualTagSerializer<ItemStack> ITEM_SERIALIZER =
            ContextualTagSerializer.fromCompound(ItemStack::fromItemNBT, ItemStack::toItemNBT);

    @Test
    void handlerWritesCopiesAndUpdatesPreserveComponentPatches(Registries registries) {
        for (var item : items()) {
            var handler = TagHandler.newHandler(registries);
            handler.setTag(REWARD, item);
            assertEquals(item, handler.getTag(REWARD));
            assertEquals(item.toItemNBT(registries), handler.getTag(REWARD.asNbt()));
            assertEquals(item, handler.copy().getTag(REWARD));
            var snapshot = handler.readableCopy();
            assertEquals(item, snapshot.getTag(REWARD));
            assertEquals(item, handler.getAndUpdateTag(REWARD, value -> value.withAmount(2)));
            assertEquals(item.withAmount(2), handler.getTag(REWARD));
            assertEquals(item, snapshot.getTag(REWARD));
            assertEquals(item.withAmount(2), TagHandler.fromCompound(handler.asCompound(), registries).getTag(REWARD));
        }
    }

    @Test
    void importedAndDirectNbtReadsPreserveComponentPatches(Registries registries) {
        for (var item : items()) {
            var nbt = CompoundBinaryTag.builder().put("reward", item.toItemNBT(registries)).build();
            assertEquals(item, REWARD.read(nbt, registries));
            assertEquals(item, REWARD.path("nested").read(CompoundBinaryTag.builder().put("nested", nbt).build(), registries));
            var handler = TagHandler.fromCompound(nbt, registries);
            assertEquals(item, handler.getTag(REWARD));
            handler.setTag(Tag.Integer("unrelated"), 7);
            assertEquals(item.toItemNBT(registries), handler.asCompound().getCompound("reward"));
            handler.setTag(Tag.Integer("count").path("reward"), 2);
            assertEquals(item.withAmount(2), handler.getTag(REWARD));
            assertEquals(item.withAmount(2), handler.readableCopy().getTag(REWARD));
        }
        var item = ItemStack.of(Material.DIAMOND_SWORD).with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        var handler = TagHandler.fromCompound(CompoundBinaryTag.builder().put("reward", item.toItemNBT(registries)).build(), registries);
        var absent = Tag.Integer("absent").path("reward", "components", "minecraft:unbreakable");
        handler.removeTag(absent);
        assertEquals(item, handler.getTag(REWARD));
        handler.updateTag(absent, value -> value);
        assertEquals(item, handler.copy().getTag(REWARD));
        assertEquals(item, handler.readableCopy().getTag(REWARD));
    }

    @Test
    void directWritesAndStandaloneValuesPreserveComponentPatches(Registries registries) {
        for (var item : items()) {
            var tag = REWARD.path("nested");
            var nbt = CompoundBinaryTag.builder().put("untouched", CompoundBinaryTag.empty());
            tag.write(nbt, item, registries);
            assertEquals(item, tag.read(nbt.build(), registries));
            assertEquals(CompoundBinaryTag.empty(), nbt.build().get("untouched"));
            assertEquals(item, ItemStack.of(Material.CHEST).withTag(tag, item, registries).getTag(tag, registries));
            assertEquals(item, Block.CHEST.withTag(tag, item, registries).getTag(tag, registries));
            Tag.Integer("sibling").path("nested").write(nbt, 3);
            assertEquals(item, tag.read(nbt.build(), registries));
        }
    }

    @Test
    void nestedSerializersListsAndViewsPreserveComponentPatches(Registries registries) {
        for (var item : items()) {
            var handler = TagHandler.newHandler(registries);
            var structure = ContextualTag.Structure("structure", ITEM_SERIALIZER);
            handler.setTag(structure, item);
            assertEquals(item, handler.getTag(structure));
            var list = structure.list().path("nested");
            handler.setTag(list, List.of(item));
            assertEquals(List.of(item), TagHandler.fromCompound(handler.asCompound(), registries).getTag(list));
            var view = ContextualTag.View(ITEM_SERIALIZER).path("view");
            handler.setTag(view, item);
            assertEquals(item, handler.getTag(view));
            handler.updateTag(view, value -> value.withAmount(2));
            assertEquals(item.withAmount(2), handler.copy().getTag(view));
        }
    }

    private static List<ItemStack> items() {
        return List.of(
                ItemStack.of(Material.DIAMOND_SWORD).with(DataComponents.UNBREAKABLE, Unit.INSTANCE),
                ItemStack.of(Material.DIAMOND_SWORD).without(DataComponents.MAX_DAMAGE));
    }
}
