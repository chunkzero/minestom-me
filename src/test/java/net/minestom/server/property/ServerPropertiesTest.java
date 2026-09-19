package net.minestom.server.property;

import net.minestom.server.Auth;
import net.minestom.server.ProcessOwned;
import net.minestom.server.ServerProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPropertiesTest {
    @Test
    void overridesTakePrecedenceAndValidateWithoutParsingUnusedValues() {
        var source = new Properties();
        source.setProperty("minestom.nbt.max-depth", "invalid");
        assertThrows(IllegalArgumentException.class, () -> ServerProperties.builder(source).build());
        var properties = ServerProperties.builder(source).nbtMaxDepth(128).build();
        assertEquals(128, properties.nbtMaxDepth().get());
        assertEquals(512, properties.nbtMaxDepth().defaultValue());
        assertFalse(properties.nbtMaxDepth().writable());
        assertThrows(IllegalArgumentException.class, () -> ServerProperties.builder(source).nbtMaxDepth(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerProperties.builder(source).nbtMaxDepth(128).maxPacketSize(2_097_152).build());
    }

    @Test
    void sourceAndBuilderDoNotChangeBuiltProperties() {
        var source = new Properties();
        source.setProperty("minestom.keep-alive-kick", "20000");
        var builder = ServerProperties.builder(source);
        source.setProperty("minestom.keep-alive-kick", "30000");
        var first = builder.build();
        var second = builder.keepAliveKick(40_000L).build();
        assertEquals(20_000L, first.keepAliveKick().get());
        assertEquals(40_000L, second.keepAliveKick().get());
        assertEquals(512, first.nbtMaxDepth().get());
    }

    @ParameterizedTest
    @CsvSource({"false,false,true", "true,false,false", "false,true,false", "true,true,false"})
    void freezingDefaultsAndOverrides(boolean insideTest, boolean unsafe, boolean expected) {
        var source = new Properties();
        source.setProperty("minestom.inside-test", Boolean.toString(insideTest));
        source.setProperty("minestom.registry.unsafe-ops", Boolean.toString(unsafe));
        assertEquals(expected, ServerProperties.builder(source).build().freezeRegistriesOnStart().get());
        assertTrue(ServerProperties.builder(source).freezeRegistriesOnStart(true).build().freezeRegistriesOnStart().get());
        assertFalse(ServerProperties.builder(source).freezeRegistriesOnStart(false).build().freezeRegistriesOnStart().get());
        source.setProperty("minestom.registry.freeze-on-start", Boolean.toString(!expected));
        assertEquals(!expected, ServerProperties.builder(source).build().freezeRegistriesOnStart().get());
        assertEquals(expected, ServerProperties.builder(source).freezeRegistriesOnStart(expected).build().freezeRegistriesOnStart().get());
    }

    @Test
    void writablePropertiesAreCopiedForEachProcess() {
        var source = new Properties();
        source.setProperty("minestom.keep-alive-kick.mutable", "true");
        var shared = ServerProperties.builder(source).keepAliveKick(20_000L).build();
        try (var first = ServerProcess.create(new Auth.Offline(), shared);
             var second = ServerProcess.create(new Auth.Offline(), shared)) {
            ProcessOwned owner = first.connectionManager();
            assertSame(first.properties(), owner.process().properties());
            assertNotSame(shared.keepAliveKick(), first.properties().keepAliveKick());
            assertNotSame(first.properties().keepAliveKick(), second.properties().keepAliveKick());
            first.properties().keepAliveKick().set(30_000L);
            assertEquals(30_000L, first.properties().keepAliveKick().get());
            assertEquals(20_000L, second.properties().keepAliveKick().get());
            assertEquals(20_000L, shared.keepAliveKick().get());
            assertThrows(IllegalArgumentException.class, () -> first.properties().keepAliveKick().set(-1L));
            assertEquals(30_000L, first.properties().keepAliveKick().get());
        }
    }

    @Test
    void startupAndEncodingPropertiesStayImmutable() {
        var source = new Properties();
        source.setProperty("minestom.properties.mutable", "true");
        source.setProperty("minestom.keep-alive-delay.mutable", "false");
        var properties = ServerProperties.builder(source).build();
        assertTrue(properties.keepAliveKick().writable());
        assertFalse(properties.keepAliveDelay().writable());
        assertFalse(properties.freezeRegistriesOnStart().writable());
        assertThrows(IllegalStateException.class, () -> properties.freezeRegistriesOnStart().set(false));
        assertThrows(IllegalStateException.class, () -> properties.nbtMaxDepth().set(64));
        assertThrows(IllegalStateException.class, () -> properties.maxPacketSize().set(128));
    }
}
