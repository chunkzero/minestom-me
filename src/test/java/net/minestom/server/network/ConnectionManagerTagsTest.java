package net.minestom.server.network;

import net.minestom.server.ServerProcess;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.common.TagsPacket;
import net.minestom.server.registry.TagKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionManagerTagsTest {
    @Test
    void onlyTheChangedRegistriesInvalidateTheirCachedTags() {
        try (var first = ServerProcess.create()) {
            var firstPacket = tags(first);
            try (var second = ServerProcess.create()) {
                assertSame(firstPacket, tags(first));
                var secondPacket = tags(second);
                var key = TagKey.<Block>ofHash("#test:first");
                first.registries().blocks().getOrCreateTag(key);

                var updated = tags(first);
                assertNotSame(firstPacket, updated);
                assertTrue(hasTag(updated, "test:first"));
                assertFalse(hasTag(secondPacket, "test:first"));
                assertSame(secondPacket, tags(second));

                first.registries().blocks().removeTag(key);
                assertFalse(hasTag(tags(first), "test:first"));
                assertSame(secondPacket, tags(second));
            }
        }
    }

    private static TagsPacket tags(ServerProcess process) {
        return (TagsPacket) process.connection().tagsPacket().packet(process.packetBuffers().context(ConnectionState.CONFIGURATION, 0));
    }

    private static boolean hasTag(TagsPacket packet, String name) {
        return packet.registries().stream().flatMap(registry -> registry.tags().stream())
                .anyMatch(tag -> tag.identifier().equals(name));
    }
}
