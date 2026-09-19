package net.minestom.server;

import net.kyori.adventure.text.Component;
import net.minestom.server.advancements.AdvancementRoot;
import net.minestom.server.advancements.FrameType;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.utils.entity.EntityFinder;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessOwnedTest {
    @Test
    void ownersKeepTheirProcessAfterItCloses() {
        try (var pair = new ServerProcessPair()) {
            var first = pair.first();
            var second = pair.second();
            var firstOwners = ownersOf(first);
            var secondOwners = ownersOf(second);
            assertOwnedBy(first, firstOwners);
            assertOwnedBy(second, secondOwners);

            first.close();
            assertThrows(IllegalStateException.class, () -> first.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)));
            assertOwnedBy(first, firstOwners);
            assertOwnedBy(second, secondOwners);
        }
    }

    private static void assertOwnedBy(ServerProcess process, List<ProcessOwned> owners) {
        for (var owner : owners) {
            assertSame(process, owner.process(), () -> owner.getClass().getSimpleName() + " changed owner");
        }
    }

    private static List<ProcessOwned> ownersOf(ServerProcess process) {
        var instance = new InstanceContainer(process, UUID.randomUUID(), DimensionType.OVERWORLD);
        var root = new AdvancementRoot(Component.text("title"), Component.text("description"),
                Material.STONE, FrameType.TASK, 0, 0, null);
        return List.of(
                instance,
                instance.getEntityTracker(),
                new DynamicChunk(instance, 0, 0),
                new Entity(process, EntityType.ZOMBIE),
                new Inventory(process, InventoryType.CHEST_1_ROW, "Chest"),
                new SilentConnection(process),
                process.teamManager().createTeam("team"),
                process.advancementManager().createTab("root", root),
                new EntityFinder(process),
                new CommandContext(process.commandManager(), "help"),
                process.server(),
                process.eventHandler(),
                process.connectionManager(),
                process.instanceManager(),
                process.commandManager(),
                process.teamManager(),
                process.advancementManager(),
                process.bossBarManager(),
                process.clickCallbackManager(),
                process.packetListenerManager());
    }

    private static final class SilentConnection extends PlayerConnection {
        SilentConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(0);
        }
    }
}
