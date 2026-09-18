package net.minestom.server.tag;

import net.kyori.adventure.text.Component;
import net.minestom.server.registry.Registries;
import net.minestom.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@RegistriesTest
public class TagComponentRegistriesTest {

    @Test
    public void get(Registries registries) {
        var component = Component.text("Hey");
        var tag = Tag.Component("component");
        var handler = TagHandler.newHandler(registries);
        handler.setTag(tag, component);
        assertEquals(component, handler.getTag(tag));
    }

    @Test
    public void empty(Registries registries) {
        var tag = Tag.Component("component");
        var handler = TagHandler.newHandler(registries);
        assertNull(handler.getTag(tag));
    }

    @Test
    public void invalidTag(Registries registries) {
        var tag = Tag.Component("entry");
        var handler = TagHandler.newHandler(registries);
        handler.setTag(Tag.Integer("entry"), 1);
        assertNull(handler.getTag(tag));
    }

    @Test
    public void nbtFallback(Registries registries) {
        var component = Component.text("Hey");
        var tag = Tag.Component("component");
        var handler = TagHandler.newHandler(registries);
        handler.setTag(tag, component);
        handler = TagHandler.fromCompound(handler.asCompound(), registries);
        assertEquals(component, handler.getTag(tag));
    }
}
