package net.minestom.server;

import net.minestom.server.advancements.AdvancementManager;
import net.minestom.server.adventure.ClickCallbackManager;
import net.minestom.server.adventure.bossbar.BossBarManager;
import net.minestom.server.command.CommandManager;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.exception.ExceptionManager;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.listener.manager.PacketListenerManager;
import net.minestom.server.network.ConnectionManager;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.socket.Server;
import net.minestom.server.recipe.RecipeManager;
import net.minestom.server.registry.Registries;
import net.minestom.server.scoreboard.TeamManager;
import net.minestom.server.snapshot.Snapshotable;
import net.minestom.server.thread.ThreadDispatcher;
import net.minestom.server.timer.SchedulerManager;
import net.minestom.server.world.Difficulty;
import org.jetbrains.annotations.ApiStatus;

import java.net.SocketAddress;

@ApiStatus.NonExtendable
public interface ServerProcess extends Snapshotable, AutoCloseable {
    /**
     * Creates a process with its own managers, configuration, and registries, without changing
     * {@link MinecraftServer#process()}.
     * <p>Gameplay and packet routing are not yet independent of the default process. In particular,
     * compression negotiation, encoded packet caches, and outgoing buffer pools still use default-process
     * state. Different compression settings cannot yet be used for independent client connections.</p>
     * {@snippet :
     * try (var first = ServerProcess.create(); var second = ServerProcess.create()) {
     *     first.setBrandName("First");
     *     first.setCompressionThreshold(0);
     *     second.setBrandName("Second");
     *     second.setCompressionThreshold(128);
     * }
     * }
     */
    static ServerProcess create(Auth auth) {
        return new ServerProcessImpl(auth);
    }

    /** Creates an independent process using offline authentication. */
    static ServerProcess create() {
        return create(new Auth.Offline());
    }

    String brandName();

    /** Updates the brand sent to this process's players. */
    void setBrandName(String brandName);

    Difficulty difficulty();

    /** Updates the difficulty sent to this process's players. */
    void setDifficulty(Difficulty difficulty);

    /** Compression threshold, or zero when compression is disabled. */
    int compressionThreshold();

    /** Sets the compression threshold before the process starts. */
    void setCompressionThreshold(int compressionThreshold);

    /**
     * Gets the registries owned by this process.
     *
     * @return the process registries
     */
    Registries registries();

    Auth auth();

    /**
     * Handles incoming connections/players.
     */
    ConnectionManager connection();

    /**
     * Handles registered instances.
     */
    InstanceManager instance();

    /**
     * Handles {@link net.minestom.server.instance.block.BlockHandler block handlers}
     * and {@link BlockPlacementRule placement rules}.
     */
    BlockManager block();

    /**
     * Handles registered commands.
     */
    CommandManager command();

    /**
     * Handles registered recipes shown to clients.
     */
    RecipeManager recipe();

    /**
     * Handles registered teams.
     */
    TeamManager team();

    /**
     * Gets the global event handler.
     * <p>
     * Used to register event callback at a global scale.
     */
    GlobalEventHandler eventHandler();

    /**
     * Main scheduler ticked at the server rate.
     */
    SchedulerManager scheduler();

    /**
     * Handles registered advancements.
     */
    AdvancementManager advancement();

    /**
     * Handles registered boss bars.
     */
    BossBarManager bossBar();

    /**
     * Handles all thrown exceptions from the server.
     */
    ExceptionManager exception();

    /**
     * Handles incoming packets.
     */
    PacketListenerManager packetListener();

    /**
     * Gets the object handling the client packets parsing.
     * <p>
     * Can be used if you want to convert a buffer to a client packet object.
     */
    PacketParser.Client packetParser();

    /**
     * Exposed socket server.
     */
    Server server();

    /**
     * Dispatcher for tickable game objects.
     */
    ThreadDispatcher<Chunk, Entity> dispatcher();

    /**
     * Handles the server ticks.
     */
    Ticker ticker();

    /**
     * The click callback manager.
     */
    ClickCallbackManager clickCallbackManager();

    /** Starts this process's socket server. A closed process cannot be started. */
    void start(SocketAddress socketAddress);

    void stop();

    @Override
    default void close() {
        stop();
    }

    boolean isAlive();

    @ApiStatus.NonExtendable
    interface Ticker {
        void tick(long nanoTime);
    }
}
