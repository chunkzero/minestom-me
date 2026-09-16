package net.minestom.server;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minestom.server.advancements.AdvancementManager;
import net.minestom.server.adventure.ClickCallbackManager;
import net.minestom.server.adventure.bossbar.BossBarManager;
import net.minestom.server.command.CommandManager;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityManager;
import net.minestom.server.event.ProcessEventHandler;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.exception.ExceptionManager;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.listener.manager.PacketListenerManager;
import net.minestom.server.monitoring.EventsJFR;
import net.minestom.server.monitoring.TickMonitor;
import net.minestom.server.network.ConnectionManager;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.play.ServerDifficultyPacket;
import net.minestom.server.network.socket.Server;
import net.minestom.server.recipe.RecipeManager;
import net.minestom.server.registry.Registries;
import net.minestom.server.scoreboard.TeamManager;
import net.minestom.server.snapshot.EntitySnapshot;
import net.minestom.server.snapshot.InstanceSnapshot;
import net.minestom.server.snapshot.ServerSnapshot;
import net.minestom.server.snapshot.SnapshotImpl;
import net.minestom.server.snapshot.SnapshotUpdater;
import net.minestom.server.thread.Acquirable;
import net.minestom.server.thread.ThreadDispatcher;
import net.minestom.server.thread.ThreadProvider;
import net.minestom.server.thread.TickSchedulerThread;
import net.minestom.server.thread.TickThread;
import net.minestom.server.timer.SchedulerManager;
import net.minestom.server.utils.PacketViewableUtils;
import net.minestom.server.utils.collection.MappedCollection;
import net.minestom.server.utils.time.Tick;
import net.minestom.server.utils.validate.Check;
import net.minestom.server.world.Difficulty;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ServerProcessImpl implements ServerProcess {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerProcessImpl.class);
    private static final AtomicInteger PROCESS_IDS = new AtomicInteger();

    private final int id = PROCESS_IDS.incrementAndGet();
    private final Auth auth;
    private volatile String brandName = "Minestom";
    private volatile Difficulty difficulty = Difficulty.NORMAL;
    private volatile int compressionThreshold = 256;

    private final ExceptionManager exception;
    private final Registries registries;

    private final ConnectionManager connection;
    private final PacketListenerManager packetListener;
    private final PacketParser.Client packetParser;
    private final InstanceManager instance;
    private final EntityManager entity;
    private final BlockManager block;
    private final CommandManager command;
    private final RecipeManager recipe;
    private final TeamManager team;
    private final ProcessEventHandler eventHandler;
    private final SchedulerManager scheduler;
    private final AdvancementManager advancement;
    private final BossBarManager bossBar;
    private final ClickCallbackManager clickCallbackManager;

    private final Server server;

    private final ThreadDispatcher<Chunk, Entity> dispatcher;
    private final Ticker ticker;
    private @Nullable TickSchedulerThread tickScheduler;
    private volatile boolean dispatcherStarted;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private @Nullable Thread shutdownHook;

    public ServerProcessImpl(Auth auth) {
        this.auth = Objects.requireNonNull(auth);
        this.exception = new ExceptionManager(this::stop);
        this.registries = Registries.vanilla();

        this.connection = new ConnectionManager(this);
        this.packetListener = new PacketListenerManager();
        this.packetParser = PacketVanilla.CLIENT_PACKET_PARSER;
        this.instance = new InstanceManager(this);
        this.entity = new EntityManager(this);
        this.block = new BlockManager();
        this.command = new CommandManager();
        this.recipe = new RecipeManager(registries);
        this.team = new TeamManager();
        this.eventHandler = new ProcessEventHandler(this);
        this.scheduler = new SchedulerManager();
        this.advancement = new AdvancementManager();
        this.bossBar = new BossBarManager();
        this.clickCallbackManager = new ClickCallbackManager();

        this.server = new Server(this, packetParser);

        this.dispatcher = ThreadDispatcher.dispatcher(this, ThreadProvider.counter(), ServerFlag.DISPATCHER_THREADS);
        this.ticker = new TickerImpl();
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public Auth auth() {
        return auth;
    }

    @Override
    public String brandName() {
        return brandName;
    }

    @Override
    public void setBrandName(String brandName) {
        this.brandName = Objects.requireNonNull(brandName);
        var packet = PluginMessagePacket.brandPacket(brandName);
        connection.getOnlinePlayers().forEach(player -> player.sendPacket(packet));
    }

    @Override
    public Difficulty difficulty() {
        return difficulty;
    }

    @Override
    public void setDifficulty(Difficulty difficulty) {
        this.difficulty = Objects.requireNonNull(difficulty);
        var packet = new ServerDifficultyPacket(difficulty, true);
        connection.getOnlinePlayers().forEach(player -> player.sendPacket(packet));
    }

    @Override
    public int compressionThreshold() {
        return compressionThreshold;
    }

    @Override
    public synchronized void setCompressionThreshold(int compressionThreshold) {
        Check.stateCondition(isAlive(), "The compression threshold cannot be changed after the server has been started.");
        this.compressionThreshold = compressionThreshold;
    }

    @Override
    public ExceptionManager exception() {
        return exception;
    }

    @Override
    public Registries registries() {
        return registries;
    }


    @Override
    public ConnectionManager connection() {
        return connection;
    }

    @Override
    public InstanceManager instance() {
        return instance;
    }

    @Override
    public EntityManager entity() {
        return entity;
    }

    @Override
    public BlockManager block() {
        return block;
    }

    @Override
    public CommandManager command() {
        return command;
    }

    @Override
    public RecipeManager recipe() {
        return recipe;
    }

    @Override
    public TeamManager team() {
        return team;
    }

    @Override
    public ProcessEventHandler eventHandler() {
        return eventHandler;
    }

    @Override
    public SchedulerManager scheduler() {
        return scheduler;
    }

    @Override
    public AdvancementManager advancement() {
        return advancement;
    }

    @Override
    public BossBarManager bossBar() {
        return bossBar;
    }

    @Override
    public PacketListenerManager packetListener() {
        return packetListener;
    }

    @Override
    public PacketParser.Client packetParser() {
        return packetParser;
    }

    @Override
    public Server server() {
        return server;
    }

    @Override
    public ThreadDispatcher<Chunk, Entity> dispatcher() {
        return dispatcher;
    }

    @Override
    public Ticker ticker() {
        return ticker;
    }

    @Override
    public ClickCallbackManager clickCallbackManager() {
        return clickCallbackManager;
    }

    @Override
    public synchronized void start(SocketAddress socketAddress) {
        Check.stateCondition(stopped.get(), "Server is closed");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Server already started");
        }

        final String brand = brandName;
        LOGGER.info("Starting {} ({}) server.", brand, Git.version());
        switch (auth) {
            case Auth.Offline _ ->
                    LOGGER.info("Running in offline mode. Beware that this is not secure and players can impersonate each other.");
            case Auth.Online _ -> LOGGER.info("Running in online mode with Mojang's authentication.");
            case Auth.Velocity _ -> LOGGER.info("Running in Velocity mode with modern IP forwarding.");
            case Auth.Bungee bungee -> {
                if (bungee.guard()) {
                    LOGGER.info("Running in BungeeCord mode, using legacy IP forwarding with Guard enabled.");
                } else {
                    LOGGER.info("Running in BungeeCord mode without BungeeGuard. Be sure to configure your firewall to prevent direct connections.");
                }
            }
        }

        // Init server
        try {
            server.init(socketAddress);
        } catch (IOException e) {
            exception.handleException(e);
            throw new RuntimeException(e);
        }

        Registries.freeze(registries);

        // Start server
        server.start();
        startDispatcher();
        tickScheduler = new TickSchedulerThread(this);
        tickScheduler.start();

        LOGGER.info("{} server started successfully.", brand);

        // Stop the server on SIGINT
        if (ServerFlag.SHUTDOWN_ON_SIGNAL) {
            shutdownHook = new Thread(this::stop, "Minestom shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    private boolean startDispatcher() {
        if (stopped.get()) return false;
        if (dispatcherStarted) return true;
        synchronized (this) {
            if (stopped.get()) return false;
            if (!dispatcherStarted) {
                if (!dispatcher.isAlive()) dispatcher.start();
                dispatcherStarted = true;
            }
        }
        return true;
    }

    @Override
    public void stop() {
        synchronized (this) {
            if (!stopped.compareAndSet(false, true)) return;
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException _) {
                    // Shutdown hooks cannot be removed once JVM shutdown has begun.
                }
                shutdownHook = null;
            }
        }
        final String brand = brandName;
        LOGGER.info("Stopping {} server.", brand);
        scheduler.shutdown();
        connection.shutdown();
        server.stop();
        LOGGER.info("Shutting down all thread pools.");
        dispatcher.shutdown();
        // A tick worker can request shutdown while the scheduler is awaiting its tick.
        if (!(Thread.currentThread() instanceof TickThread) && Thread.currentThread() != tickScheduler) {
            try {
                if (tickScheduler != null) tickScheduler.join();
                for (var thread : dispatcher.threads()) thread.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        LOGGER.info("{} server stopped successfully.", brand);
    }

    @Override
    public boolean isAlive() {
        return started.get() && !stopped.get();
    }

    @Override
    public ServerSnapshot updateSnapshot(SnapshotUpdater updater) {
        List<AtomicReference<InstanceSnapshot>> instanceRefs = new ArrayList<>();
        Int2ObjectOpenHashMap<AtomicReference<EntitySnapshot>> entityRefs = new Int2ObjectOpenHashMap<>();
        for (Instance instance : instance.getInstances()) {
            instanceRefs.add(updater.reference(instance));
            for (Entity entity : instance.getEntities()) {
                entityRefs.put(entity.getEntityId(), updater.reference(entity));
            }
        }
        return new SnapshotImpl.Server(MappedCollection.plainReferences(instanceRefs), entityRefs);
    }

    private final class TickerImpl implements Ticker {
        @Override
        public synchronized void tick(long nanoTime) {
            if (!startDispatcher()) {
                Check.stateCondition(Thread.currentThread() != tickScheduler, "Server is closed");
                return;
            }
            var serverTickEvent = EventsJFR.newServerTick();
            serverTickEvent.begin();
            scheduler().processTick();

            // Connection tick (let waiting clients in, send keep alives, handle configuration players packets)
            connection().tick(nanoTime);

            // Server tick (chunks/entities)
            serverTick(nanoTime);

            // The click callback provider needs ticking to clean up the cache.
            clickCallbackManager().tick(nanoTime);

            scheduler().processTickEnd();

            // Flush all waiting packets
            PacketViewableUtils.flush();

            // Monitoring
            {
                final double acquisitionTimeMs = Acquirable.resetAcquiringTime() / 1e6D;
                final double tickTimeMs = (System.nanoTime() - nanoTime) / 1e6D;
                final TickMonitor tickMonitor = new TickMonitor(tickTimeMs, acquisitionTimeMs);
                eventHandler.call(new ServerTickMonitorEvent(tickMonitor));
            }
            serverTickEvent.commit();
        }

        private void serverTick(long nanoStart) {
            long milliStart = TimeUnit.NANOSECONDS.toMillis(nanoStart);
            // Tick all instances
            for (Instance instance : instance().getInstances()) {
                try {
                    instance.tick(milliStart);
                } catch (Exception e) {
                    exception().handleException(e);
                }
            }
            // Tick all chunks (and entities inside)
            dispatcher().updateAndAwait(nanoStart);

            // Clear removed entities & update threads
            final long tickDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanoStart);
            final long remainingTickDuration = Tick.SERVER_TICKS.getDuration().toNanos() - tickDuration;
            // the nanoTimeout for refreshThreads is the remaining tick duration
            dispatcher().refreshThreads(remainingTickDuration);
        }
    }
}
