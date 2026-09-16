package net.minestom.server.event;

import net.minestom.server.ServerProcess;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityManager;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.entity.EntityItemMergeEvent;
import net.minestom.server.event.entity.EntityShootEvent;
import net.minestom.server.event.entity.projectile.ProjectileCollideWithEntityEvent;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.instance.InstanceChunkUnloadEvent;
import net.minestom.server.event.item.PickupExperienceEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerPickEntityEvent;
import net.minestom.server.event.player.PlayerSpectateEntityEvent;
import net.minestom.server.event.player.PlayerTeleportToEntityEvent;
import net.minestom.server.event.server.ClientPingServerEvent;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.event.trait.EntityEvent;
import net.minestom.server.event.trait.InstanceEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.network.ConnectionManager;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.network.socket.Server;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

final class EventOwnership {
    private EventOwnership() {
    }

    static void checkTarget(ServerProcess process, @Nullable Object value) {
        final ServerProcess owner = switch (value) {
            case ServerProcess target -> target;
            case Entity entity -> entity.process();
            case EntityManager manager -> manager.process();
            case Instance instance -> instance.process();
            case Chunk chunk -> chunk.getInstance().process();
            case PlayerConnection connection -> connection.process();
            case ConnectionManager manager -> manager.process();
            case InstanceManager manager -> manager.process();
            case EntityTracker tracker -> tracker.process();
            case Server server -> server.process();
            case EventNode<?> node -> node.process();
            case null, default -> null;
        };
        Check.argCondition(owner != null && owner != process, "Event target belongs to another process");
    }

    static void checkEvent(ServerProcess process, Event event) {
        if (event instanceof EntityEvent entityEvent) checkTarget(process, entityEvent.getEntity());
        if (event instanceof InstanceEvent instanceEvent) checkTarget(process, instanceEvent.getInstance());
        switch (event) {
            case EntityAttackEvent attack -> checkTarget(process, attack.getTarget());
            case EntityDamageEvent damage -> {
                checkTarget(process, damage.getDamage().getAttacker());
                checkTarget(process, damage.getDamage().getSource());
            }
            case EntityShootEvent shoot -> checkTarget(process, shoot.getProjectile());
            case EntityItemMergeEvent merge -> checkTarget(process, merge.getMerged());
            case ProjectileCollideWithEntityEvent collision -> checkTarget(process, collision.getTarget());
            case PickupItemEvent pickup -> checkTarget(process, pickup.getItemEntity());
            case PickupExperienceEvent pickup -> checkTarget(process, pickup.getExperienceOrb());
            case PlayerEntityInteractEvent interact -> checkTarget(process, interact.getTarget());
            case PlayerPickEntityEvent pick -> checkTarget(process, pick.getTarget());
            case PlayerSpectateEntityEvent spectate -> checkTarget(process, spectate.getTarget());
            case PlayerTeleportToEntityEvent teleport -> checkTarget(process, teleport.getTarget());
            case InstanceChunkLoadEvent load -> checkTarget(process, load.getChunk());
            case InstanceChunkUnloadEvent unload -> checkTarget(process, unload.getChunk());
            case AsyncPlayerConfigurationEvent configuration -> checkTarget(process, configuration.getSpawningInstance());
            case AsyncPlayerPreLoginEvent login -> checkTarget(process, login.getConnection());
            case ClientPingServerEvent ping -> checkTarget(process, ping.getConnection());
            case ServerListPingEvent ping -> checkTarget(process, ping.getConnection());
            default -> {
            }
        }
    }
}
