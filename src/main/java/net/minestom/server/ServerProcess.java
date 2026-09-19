package net.minestom.server;

import net.minestom.server.advancements.AdvancementManager;
import net.minestom.server.adventure.ClickCallbackManager;
import net.minestom.server.adventure.ComponentTranslation;
import net.minestom.server.adventure.audience.Audiences;
import net.minestom.server.adventure.bossbar.BossBarManager;
import net.minestom.server.command.CommandManager;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.ProcessEventHandler;
import net.minestom.server.exception.ExceptionManager;
import net.minestom.server.extras.lan.OpenToLAN;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.listener.manager.PacketListenerManager;
import net.minestom.server.network.ConnectionManager;
import net.minestom.server.network.packet.PacketBatcher;
import net.minestom.server.network.packet.PacketBufferPool;
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
     * Creates an independent process with its own managers, configuration, and registries.
     * Retain the process and close it when its runtime is no longer needed.
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

    /** Identifier unique to this process within the JVM, used to distinguish its threads and diagnostics. */
    int id();

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

    /** Component translation settings used by this process's players and bound serializers. */
    ComponentTranslation translation();

    /** LAN advertisements owned and closed by this process. */
    OpenToLAN lan();

    /** Allocates a window ID within this process, wrapping through the protocol's 1–127 range. */
    @ApiStatus.Internal
    byte generateInventoryId();

    /**
     * Handles incoming connections/players.
     */
    ConnectionManager connectionManager();

    /**
     * Handles registered instances.
     */
    InstanceManager instanceManager();

    /**
     * Allocates an entity ID shared by all instances in this process, including packet-only entities.
     * IDs from different processes may overlap; identify an entity by its process and ID together.
     */
    int generateEntityId();

    /**
     * Handles {@link net.minestom.server.instance.block.BlockHandler block handlers}
     * and {@link BlockPlacementRule placement rules}.
     */
    BlockManager blockManager();

    /**
     * Handles registered commands.
     */
    CommandManager commandManager();

    /**
     * Handles registered recipes shown to clients.
     */
    RecipeManager recipeManager();

    /**
     * Handles registered teams.
     */
    TeamManager teamManager();

    /**
     * Gets the event root owned by this process.
     * <p>
     * Used to register event callbacks for this process.
     */
    ProcessEventHandler eventHandler();

    /**
     * Main scheduler ticked at the server rate.
     */
    SchedulerManager schedulerManager();

    /**
     * Handles registered advancements.
     */
    AdvancementManager advancementManager();

    /**
     * Handles registered boss bars.
     */
    BossBarManager bossBarManager();

    /**
     * Provides this process's players, console, and custom audience registrations.
     */
    Audiences audiences();

    /**
     * Handles all thrown exceptions from the server.
     */
    ExceptionManager exceptionManager();

    /**
     * Handles incoming packets.
     */
    PacketListenerManager packetListenerManager();

    /**
     * Gets the object handling the client packets parsing.
     * <p>
     * Can be used if you want to convert a buffer to a client packet object.
     */
    PacketParser.Client packetParser();

    /** Registry-bound packet buffers, released when this process closes. */
    @ApiStatus.Internal
    PacketBufferPool packetBuffers();

    /** Pending viewable packets flushed by this process's tick. */
    @ApiStatus.Internal
    PacketBatcher packetBatcher();

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

    /** Starts this process's socket server, dispatcher, and tick scheduler. A failed start closes the process; a closed process cannot be restarted. */
    void start(SocketAddress socketAddress);

    /**
     * Stops this process, closing its schedulers before shutdown callbacks and player disconnection.
     * Teardown during process shutdown cannot submit scheduled work; perform required cleanup directly.
     */
    void stop();

    @Override
    default void close() {
        stop();
    }

    boolean isAlive();

    @ApiStatus.NonExtendable
    interface Ticker {
        /** Runs one tick, starting this process's dispatcher on first use. Also usable before socket startup. */
        void tick(long nanoTime);
    }
}
