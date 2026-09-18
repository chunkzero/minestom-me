package net.minestom.server.tag;

import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.registry.Registries;
import net.minestom.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static net.minestom.testing.TestUtils.assertEqualsSNBT;
import static net.minestom.testing.TestUtils.waitUntilCleared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@RegistriesTest
public class TagItemRegistriesTest {

    @Test
    public void get(Registries registries) {
        var item = ItemStack.of(Material.DIAMOND);
        var tag = Tag.ItemStack("item");
        var handler = TagHandler.newHandler(registries);
        handler.setTag(tag, item);

        assertEquals(item, handler.getTag(tag));
    }

    @Test
    public void getDifferentObject(Registries registries) {
        var item = ItemStack.of(Material.DIAMOND);
        var handler = TagHandler.newHandler(registries);
        handler.setTag(Tag.ItemStack("item"), item);

        assertEquals(item, handler.getTag(Tag.ItemStack("item")));
    }

    @Test
    public void remove(Registries registries) {
        var item = ItemStack.of(Material.DIAMOND);
        var tag = Tag.ItemStack("item");
        var handler = TagHandler.newHandler(registries);
        handler.setTag(tag, item);
        assertEquals(item, handler.getTag(tag));

        handler.setTag(tag, null);
        assertNull(handler.getTag(tag));
    }

    @Test
    public void gc(Registries registries) {
        var item = ItemStack.of(Material.DIAMOND);
        var tag = Tag.ItemStack("item");
        var handler = TagHandler.newHandler(registries);
        handler.setTag(tag, item);
        assertEquals(item, handler.getTag(tag));
        handler.setTag(tag, null);

        var ref = new WeakReference<>(item);
        //noinspection UnusedAssignment
        item = null;
        waitUntilCleared(ref);
    }

    @Test
    public void invalidation(Registries registries) {
        var item = ItemStack.of(Material.DIAMOND);
        var item2 = ItemStack.of(Material.DIAMOND, 2);
        var handler = TagHandler.newHandler(registries);

        var tag = Tag.ItemStack("item");
        handler.setTag(tag, item);
        assertEquals(item, handler.getTag(tag));
        handler.setTag(tag, item2);
        assertEquals(item2, handler.getTag(tag));
    }

    @Test
    public void differentTagInvalidation(Registries registries) {
        var item = ItemStack.of(Material.DIAMOND);
        var item2 = ItemStack.of(Material.DIAMOND, 2);
        var handler = TagHandler.newHandler(registries);

        var itemTag = Tag.ItemStack("item");
        var nbtTag = Tag.NBT("item");
        // Write the item using the ItemStack tag
        {
            handler.setTag(itemTag, item);
            assertEquals(item, handler.getTag(itemTag));
            assertEquals(item.toItemNBT(registries), handler.getTag(nbtTag));
        }
        // Override it with an NBT tag
        {
            handler.setTag(nbtTag, item2.toItemNBT(registries));
            assertEquals(item2, handler.getTag(itemTag));
            assertEquals(item2.toItemNBT(registries), handler.getTag(nbtTag));
        }
    }

    @Test
    public void snbt(Registries registries) {
        var handler = TagHandler.newHandler(registries);
        var tag = Tag.ItemStack("item");
        handler.setTag(tag, ItemStack.of(Material.DIAMOND));
        assertEqualsSNBT("""
                {
                  "item": {
                    "id":"minecraft:diamond",
                    "count":1
                  }
                }
                """, handler.asCompound());
        handler.removeTag(tag);
        assertEqualsSNBT("{}", handler.asCompound());
    }
}
