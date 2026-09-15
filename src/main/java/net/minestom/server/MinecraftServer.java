package net.minestom.server;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.minestom.server.advancements.AdvancementManager;
import net.minestom.server.adventure.ClickCallbackManager;
import net.minestom.server.adventure.bossbar.BossBarManager;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.command.CommandManager;
import net.minestom.server.dialog.Dialog;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.metadata.animal.ChickenVariant;
import net.minestom.server.entity.metadata.animal.CowVariant;
import net.minestom.server.entity.metadata.animal.FrogVariant;
import net.minestom.server.entity.metadata.animal.PigVariant;
import net.minestom.server.entity.metadata.animal.ZombieNautilusVariant;
import net.minestom.server.entity.metadata.animal.tameable.CatVariant;
import net.minestom.server.entity.metadata.animal.tameable.WolfSoundVariant;
import net.minestom.server.entity.metadata.animal.tameable.WolfVariant;
import net.minestom.server.entity.metadata.cube.SulfurCubeArchetype;
import net.minestom.server.entity.metadata.other.PaintingVariant;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.exception.ExceptionManager;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.block.banner.BannerPattern;
import net.minestom.server.instance.block.jukebox.JukeboxSong;
import net.minestom.server.instance.block.predicate.DataComponentPredicate;
import net.minestom.server.item.armor.TrimMaterial;
import net.minestom.server.item.armor.TrimPattern;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.item.enchant.EntityEffect;
import net.minestom.server.item.enchant.LevelBasedValue;
import net.minestom.server.item.enchant.LocationEffect;
import net.minestom.server.item.enchant.ValueEffect;
import net.minestom.server.item.instrument.Instrument;
import net.minestom.server.listener.manager.PacketListenerManager;
import net.minestom.server.message.ChatType;
import net.minestom.server.network.ConnectionManager;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.socket.Server;
import net.minestom.server.recipe.RecipeManager;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.Registries;
import net.minestom.server.scoreboard.TeamManager;
import net.minestom.server.thread.TickSchedulerThread;
import net.minestom.server.timer.SchedulerManager;
import net.minestom.server.world.Difficulty;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;
import net.minestom.server.world.clock.WorldClock;
import net.minestom.server.world.timeline.Timeline;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.UnknownNullability;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

/**
 * Temporary facade for the default server process, scheduled for deletion.
 * <p>New code must retain an explicit {@link ServerProcess} and use its managers and registries.
 * Do not add default-process accessors or context-free compatibility overloads.</p>
 * <p>The default-process bootstrap remains necessary until gameplay ownership and ticking are migrated.
 * {@link ServerProcess#create()} currently supports independent construction and configuration only.
 * Static settings require initialization first; each {@link #init()} installs fresh defaults.</p>
 */
public final class MinecraftServer implements MinecraftConstants {

    public static final ComponentLogger LOGGER = ComponentLogger.logger(MinecraftServer.class);

    // Threads
    public static final String THREAD_NAME_BENCHMARK = "Ms-Benchmark";

    public static final String THREAD_NAME_TICK_SCHEDULER = "Ms-TickScheduler";
    public static final String THREAD_NAME_TICK = "Ms-Tick";

    // Config
    // Can be modified at performance cost when increased
    @Deprecated
    public static final int TICK_PER_SECOND = ServerFlag.SERVER_TICKS_PER_SECOND;
    public static final int TICK_MS = 1000 / TICK_PER_SECOND;

    // In-Game Manager
    private static volatile @UnknownNullability ServerProcess serverProcess;

    /**
     * @deprecated Temporary default-process bootstrap; scheduled for deletion with the remaining engine ownership migration.
     */
    @Deprecated(forRemoval = true)
    public static MinecraftServer init(Auth auth) {
        updateProcess(auth);
        return new MinecraftServer();
    }

    /**
     * @deprecated Temporary default-process bootstrap; scheduled for deletion with the remaining engine ownership migration.
     */
    @Deprecated(forRemoval = true)
    public static MinecraftServer init() {
        return init(new Auth.Offline());
    }

    /**
     * @deprecated Temporary default-process bootstrap; scheduled for deletion with the remaining engine ownership migration.
     */
    @ApiStatus.Internal
    @Deprecated(forRemoval = true)
    public static ServerProcess updateProcess(Auth auth) {
        ServerProcess process = ServerProcess.create(auth);
        serverProcess = process;
        return process;
    }

    /**
     * @deprecated Temporary default-process bootstrap; scheduled for deletion with the remaining engine ownership migration.
     */
    @ApiStatus.Internal
    @Deprecated(forRemoval = true)
    public static ServerProcess updateProcess() {
        return updateProcess(new Auth.Offline());
    }

    /**
     * Gets the current server brand name.
     *
     * @return the server brand name
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#brandName()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static String getBrandName() {
        return process().brandName();
    }

    /**
     * Changes the server brand name and send the change to all connected players.
     *
     * @param brandName the server brand name
     * @throws NullPointerException if {@code brandName} is null
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#setBrandName(String)} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static void setBrandName(String brandName) {
        Objects.requireNonNull(brandName);
        process().setBrandName(brandName);
    }

    /**
     * Gets the server difficulty showed in game option.
     *
     * @return the server difficulty
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#difficulty()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static Difficulty getDifficulty() {
        return process().difficulty();
    }

    /**
     * Changes the server difficulty and send the appropriate packet to all connected clients.
     *
     * @param difficulty the new server difficulty
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#setDifficulty(Difficulty)} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static void setDifficulty(Difficulty difficulty) {
        Objects.requireNonNull(difficulty);
        process().setDifficulty(difficulty);
    }

    /**
     * @deprecated Scheduled for deletion. Retain and pass the owning {@link ServerProcess} explicitly.
     */
    @Deprecated(forRemoval = true)
    public static @UnknownNullability ServerProcess process() {
        return serverProcess;
    }

    /**
     * Gets the registries owned by the current server process.
     *
     * @return the current server registries
     * @throws NullPointerException if the server has not been initialized
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#registries()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static Registries getRegistries() {
        return Objects.requireNonNull(serverProcess, "serverProcess").registries();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#eventHandler()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static GlobalEventHandler getGlobalEventHandler() {
        return serverProcess.eventHandler();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#packetListener()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static PacketListenerManager getPacketListenerManager() {
        return serverProcess.packetListener();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#instance()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static InstanceManager getInstanceManager() {
        return serverProcess.instance();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#block()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static BlockManager getBlockManager() {
        return serverProcess.block();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#command()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static CommandManager getCommandManager() {
        return serverProcess.command();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#recipe()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static RecipeManager getRecipeManager() {
        return serverProcess.recipe();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#team()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static TeamManager getTeamManager() {
        return serverProcess.team();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#scheduler()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static SchedulerManager getSchedulerManager() {
        return serverProcess.scheduler();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#exception()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static ExceptionManager getExceptionManager() {
        return serverProcess.exception();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#connection()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static ConnectionManager getConnectionManager() {
        return serverProcess.connection();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#bossBar()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static BossBarManager getBossBarManager() {
        return serverProcess.bossBar();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#packetParser()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static PacketParser.Client getPacketParser() {
        return serverProcess.packetParser();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#isAlive()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static boolean isStarted() {
        return serverProcess.isAlive();
    }

    /**
     * @deprecated Scheduled for deletion. Use the owning process lifecycle; this method returns {@code !process.isAlive()}.
     */
    @Deprecated(forRemoval = true)
    public static boolean isStopping() {
        return !isStarted();
    }

    /**
     * Gets the chunk view distance of the server.
     * <p>
     * Deprecated in favor of {@link ServerFlag#CHUNK_VIEW_DISTANCE}
     *
     * @return the chunk view distance
     */
    @Deprecated
    public static int getChunkViewDistance() {
        return ServerFlag.CHUNK_VIEW_DISTANCE;
    }

    /**
     * Gets the entity view distance of the server.
     * <p>
     * Deprecated in favor of {@link ServerFlag#ENTITY_VIEW_DISTANCE}
     *
     * @return the entity view distance
     */
    @Deprecated
    public static int getEntityViewDistance() {
        return ServerFlag.ENTITY_VIEW_DISTANCE;
    }

    /**
     * Gets the compression threshold of the server.
     *
     * @return the compression threshold, 0 means that compression is disabled
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#compressionThreshold()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static int getCompressionThreshold() {
        return process().compressionThreshold();
    }

    /**
     * Changes the compression threshold of the server.
     * <p>
     * WARNING: this need to be called before {@link #start(SocketAddress)}.
     *
     * @param compressionThreshold the new compression threshold, 0 to disable compression
     * @throws IllegalStateException if this is called after the server started
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#setCompressionThreshold(int)} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static void setCompressionThreshold(int compressionThreshold) {
        process().setCompressionThreshold(compressionThreshold);
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#advancement()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static AdvancementManager getAdvancementManager() {
        return serverProcess.advancement();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#clickCallbackManager()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static ClickCallbackManager getClickCallbackManager() {
        return serverProcess.clickCallbackManager();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#chatType()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<ChatType> getChatTypeRegistry() {
        return serverProcess.chatType();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#dialog()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<Dialog> getDialogRegistry() {
        return serverProcess.dialog();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#dimensionType()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<DimensionType> getDimensionTypeRegistry() {
        return serverProcess.dimensionType();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#biome()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<Biome> getBiomeRegistry() {
        return serverProcess.biome();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#damageType()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<DamageType> getDamageTypeRegistry() {
        return serverProcess.damageType();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#trimMaterial()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<TrimMaterial> getTrimMaterialRegistry() {
        return serverProcess.trimMaterial();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#trimPattern()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<TrimPattern> getTrimPatternRegistry() {
        return serverProcess.trimPattern();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#bannerPattern()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<BannerPattern> getBannerPatternRegistry() {
        return serverProcess.bannerPattern();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#wolfVariant()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<WolfVariant> getWolfVariantRegistry() {
        return serverProcess.wolfVariant();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#wolfSoundVariant()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<WolfSoundVariant> getWolfSoundVariantRegistry() {
        return serverProcess.wolfSoundVariant();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#catVariant()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<CatVariant> getCatVariantRegistry() {
        return serverProcess.catVariant();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#chickenVariant()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<ChickenVariant> getChickenVariantRegistry() {
        return serverProcess.chickenVariant();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#cowVariant()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<CowVariant> getCowVariantRegistry() {
        return serverProcess.cowVariant();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#frogVariant()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<FrogVariant> getFrogVariantRegistry() {
        return serverProcess.frogVariant();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#pigVariant()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<PigVariant> getPigVariantRegistry() {
        return serverProcess.pigVariant();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#zombieNautilusVariant()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<ZombieNautilusVariant> getZombieNautilusVariantRegistry() {
        return serverProcess.zombieNautilusVariant();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#enchantment()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<Enchantment> getEnchantmentRegistry() {
        return serverProcess.enchantment();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#paintingVariant()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<PaintingVariant> getPaintingVariantRegistry() {
        return serverProcess.paintingVariant();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#jukeboxSong()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<JukeboxSong> getJukeboxSongRegistry() {
        return serverProcess.jukeboxSong();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#instrument()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<Instrument> getInstrumentRegistry() {
        return serverProcess.instrument();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#timeline()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<Timeline> getTimelineRegistry() {
        return serverProcess.timeline();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#worldClock()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<WorldClock> getWorldClockRegistry() {
        return serverProcess.worldClock();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#sulfurCubeArchetype()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<SulfurCubeArchetype> getSulfurCubeArchetypeRegistry() {
        return serverProcess.sulfurCubeArchetype();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#enchantmentLevelBasedValues()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<StructCodec<? extends LevelBasedValue>> enchantmentLevelBasedValues() {
        return serverProcess.enchantmentLevelBasedValues();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#enchantmentValueEffects()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<StructCodec<? extends ValueEffect>> enchantmentValueEffects() {
        return serverProcess.enchantmentValueEffects();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#enchantmentEntityEffects()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<StructCodec<? extends EntityEffect>> enchantmentEntityEffects() {
        return serverProcess.enchantmentEntityEffects();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#enchantmentLocationEffects()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<StructCodec<? extends LocationEffect>> enchantmentLocationEffects() {
        return serverProcess.enchantmentLocationEffects();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#componentPredicateTypes()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static DynamicRegistry<Codec<? extends DataComponentPredicate>> componentPredicateTypes() {
        return serverProcess.componentPredicateTypes();
    }

    /**
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#server()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static Server getServer() {
        return serverProcess.server();
    }

    /**
     * Starts the server.
     * <p>
     * It should be called after {@link #init()} and probably your own initialization code.
     *
     * @param address the server address
     * @throws IllegalStateException if called before {@link #init()} or if the server is already running
     * @deprecated Temporary default-process bootstrap; scheduled for deletion with the remaining engine ownership migration.
     */
    @Deprecated(forRemoval = true)
    public void start(SocketAddress address) {
        serverProcess.start(address);
        serverProcess.dispatcher().start();
        new TickSchedulerThread(serverProcess).start();
    }

    /**
     * @deprecated Temporary default-process bootstrap; scheduled for deletion with the remaining engine ownership migration.
     */
    @Deprecated(forRemoval = true)
    public void start(String address, int port) {
        start(new InetSocketAddress(address, port));
    }

    /**
     * Stops this server properly (saves if needed, kicking players, etc.)
     * @deprecated Scheduled for deletion. Use {@link ServerProcess#stop()} on the owning process.
     */
    @Deprecated(forRemoval = true)
    public static void stopCleanly() {
        serverProcess.stop();
    }
}
