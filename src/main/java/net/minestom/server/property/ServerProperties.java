package net.minestom.server.property;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Process-owned configuration loaded from system properties, with typed construction overrides.
 *
 * <p>Build with {@link #builder()} and supply the result to
 * {@link net.minestom.server.ServerProcess#create(net.minestom.server.Auth, ServerProperties)}.
 * Explicit values take precedence over system properties. Unspecified values are resolved once at
 * build time. Each process copies the configuration so writable properties are never shared.</p>
 *
 * <p>Properties are immutable unless {@code <name>.mutable=true}, falling back to
 * {@code minestom.properties.mutable}. Startup settings and serialization limits are always immutable.
 * Remaining static properties are JVM-wide; they have not migrated to process ownership.</p>
 */
public final class ServerProperties {

    // Server Behavior
    public static final ServerProperty<Integer> SERVER_TICKS_PER_SECOND = Integer("minestom.tps", 20);
    public static final ServerProperty<Integer> SERVER_MAX_TICK_CATCH_UP = Integer("minestom.max-tick-catch-up", 5);
    public static final ServerProperty<Integer> CHUNK_VIEW_DISTANCE = Integer("minestom.chunk-view-distance", 8); // Base chunk view distance of instances and client settings
    public static final ServerProperty<Integer> ENTITY_VIEW_DISTANCE = Integer("minestom.entity-view-distance", 5);
    public static final ServerProperty<Integer> ENTITY_SYNCHRONIZATION_TICKS = Integer("minestom.entity-synchronization-ticks", 20);
    public static final ServerProperty<Integer> SEND_LIGHT_AFTER_BLOCK_PLACEMENT_DELAY = Integer("minestom.send-light-after-block-placement-delay", 100);

    // Network rate limiting
    public static final ServerProperty<Integer> PLAYER_CHUNK_UPDATE_LIMITER_HISTORY_SIZE = Integer("minestom.player.chunk-update-limiter-history-size", 5, 0, Integer.MAX_VALUE);

    // Chunk update
    public static final ServerProperty<Float> MIN_CHUNKS_PER_TICK = Float("minestom.chunk-queue.min-per-tick", 0.01f);
    public static final ServerProperty<Float> MAX_CHUNKS_PER_TICK = Float("minestom.chunk-queue.max-per-tick", 64.0f);
    public static final ServerProperty<Float> CHUNKS_PER_TICK_MULTIPLIER = Float("minestom.chunk-queue.multiplier", 1f);

    // Packet sending optimizations
    public static final ServerProperty<Boolean> GROUPED_PACKET = Boolean("minestom.grouped-packet", true);
    public static final ServerProperty<Boolean> VIEWABLE_PACKET = Boolean("minestom.viewable-packet", true);

    // Tags
    public static final ServerProperty<Boolean> TAG_HANDLER_CACHE_ENABLED = Boolean("minestom.tag-handler-cache", true);
    public static final ServerProperty<Boolean> SERIALIZE_EMPTY_COMPOUND = Boolean("minestom.serialization.serialize-empty-nbt-compound", false);

    // Online Mode
    public static final ServerProperty<String> AUTH_URL = String("minestom.auth.url", "https://sessionserver.mojang.com/session/minecraft/hasJoined");
    public static final ServerProperty<Boolean> AUTH_PREVENT_PROXY_CONNECTIONS = Boolean("minestom.auth.prevent-proxy-connections", false);

    // World
    public static final ServerProperty<Integer> WORLD_BORDER_SIZE = Integer("minestom.world-border-size", 29999984);

    // Entities
    public static final ServerProperty<Boolean> ENFORCE_INTERACTION_LIMIT = Boolean("minestom.enforce-entity-interaction-range", true);

    // Testing
    public static final ServerProperty<Boolean> INSIDE_TEST = Boolean("minestom.inside-test", false);

    // Experimental/Unstable
    @ApiStatus.Experimental
    public static final ServerProperty<Boolean> REGISTRY_UNSAFE_OPS = Boolean("minestom.registry.unsafe-ops", false);
    @ApiStatus.Experimental
    public static final ServerProperty<Boolean> ACQUIRABLE_STRICT = Boolean("minestom.acquirable-strict", false);
    @ApiStatus.Experimental
    public static final ServerProperty<Boolean> UNSAFE_COLLECTIONS = Boolean("minestom.unsafe-collections", false); // Likely to be removed in the future
    @ApiStatus.Experimental
    public static final ServerProperty<Boolean> TEMPLATE_COMPILER = Boolean("minestom.template-compiler", false);

    private final ServerProperty<Boolean> freezeRegistriesOnStart;
    private final ServerProperty<Boolean> shutdownOnSignal;
    private final ServerProperty<Integer> dispatcherThreads;
    private final ServerProperty<Long> loginPluginMessageTimeout;
    private final ServerProperty<Long> knownPacksResponseTimeout;
    private final ServerProperty<Boolean> acceptTransfers;
    private final ServerProperty<Boolean> automaticComponentTranslation;
    private final ServerProperty<Integer> playerPacketPerTick;
    private final ServerProperty<Integer> playerPacketQueueSize;
    private final ServerProperty<Long> keepAliveDelay;
    private final ServerProperty<Long> keepAliveKick;
    private final ServerProperty<Boolean> suppressConnectionAcceptErrors;
    private final ServerProperty<Boolean> suppressConnectionIoErrors;
    private final ServerProperty<Integer> suppressMalformedPacketErrorLevel;
    private final ServerProperty<Integer> suppressMisusedPacketErrorLevel;
    private final ServerProperty<Boolean> rejectMalformedPacket;
    private final ServerProperty<Boolean> rejectMisusedPacket;
    private final ServerProperty<Boolean> warnPacketUnreadBytes;
    private final ServerProperty<Integer> maxPacketSize;
    private final ServerProperty<Integer> maxPacketSizePreAuth;
    private final ServerProperty<Integer> socketSendBufferSize;
    private final ServerProperty<Integer> socketReceiveBufferSize;
    private final ServerProperty<Boolean> socketNoDelay;
    private final ServerProperty<Integer> socketTimeout;
    private final ServerProperty<Integer> pooledBufferSize;
    private final ServerProperty<Boolean> cachedPacket;
    private final ServerProperty<Boolean> fasterSocketWrites;
    private final ServerProperty<Boolean> proxyProtocol;
    private final ServerProperty<Boolean> proxyProtocolRequired;
    private final ServerProperty<Integer> nbtMaxDepth;
    private final ServerProperty<Integer> nbtMaxBytes;

    private ServerProperties(Builder builder, Properties source) {
        boolean freezeByDefault = !Boolean.parseBoolean(source.getProperty("minestom.inside-test", "false"))
                && !Boolean.parseBoolean(source.getProperty("minestom.registry.unsafe-ops", "false"));
        this.freezeRegistriesOnStart = ServerPropertyImpl.create(source, "minestom.registry.freeze-on-start", freezeByDefault,
                Boolean::parseBoolean, null, builder.freezeRegistriesOnStart, false);
        this.shutdownOnSignal = ServerPropertyImpl.create(source, "minestom.shutdown-on-signal", true,
                Boolean::parseBoolean, null, builder.shutdownOnSignal, false);
        this.dispatcherThreads = ServerPropertyImpl.create(source, "minestom.dispatcher-threads", 1,
                Integer::parseInt, range("minestom.dispatcher-threads", 1, Integer.MAX_VALUE), builder.dispatcherThreads, false);
        this.loginPluginMessageTimeout = ServerPropertyImpl.create(source, "minestom.login-plugin-message-timeout", 5_000L,
                Long::parseLong, range("minestom.login-plugin-message-timeout", 0L, Long.MAX_VALUE), builder.loginPluginMessageTimeout, true);
        this.knownPacksResponseTimeout = ServerPropertyImpl.create(source, "minestom.known-packs-response-timeout", 5 * 60_000L,
                Long::parseLong, range("minestom.known-packs-response-timeout", 0L, Long.MAX_VALUE), builder.knownPacksResponseTimeout, true);
        this.acceptTransfers = ServerPropertyImpl.create(source, "minestom.accept-transfers", false,
                Boolean::parseBoolean, null, builder.acceptTransfers, true);
        this.automaticComponentTranslation = ServerPropertyImpl.create(source, "minestom.automatic-component-translation", false,
                Boolean::parseBoolean, null, builder.automaticComponentTranslation, true);
        this.playerPacketPerTick = ServerPropertyImpl.create(source, "minestom.packet-per-tick", 50,
                Integer::parseInt, range("minestom.packet-per-tick", 1, Integer.MAX_VALUE), builder.playerPacketPerTick, true);
        this.playerPacketQueueSize = ServerPropertyImpl.create(source, "minestom.packet-queue-size", 1000,
                Integer::parseInt, range("minestom.packet-queue-size", 1, Integer.MAX_VALUE), builder.playerPacketQueueSize, false);
        this.keepAliveDelay = ServerPropertyImpl.create(source, "minestom.keep-alive-delay", 10_000L,
                Long::parseLong, range("minestom.keep-alive-delay", 0L, Long.MAX_VALUE), builder.keepAliveDelay, true);
        this.keepAliveKick = ServerPropertyImpl.create(source, "minestom.keep-alive-kick", 15_000L,
                Long::parseLong, range("minestom.keep-alive-kick", 0L, Long.MAX_VALUE), builder.keepAliveKick, true);
        this.suppressConnectionAcceptErrors = ServerPropertyImpl.create(source, "minestom.suppress-connection-accept-errors", true,
                Boolean::parseBoolean, null, builder.suppressConnectionAcceptErrors, true);
        this.suppressConnectionIoErrors = ServerPropertyImpl.create(source, "minestom.suppress-connection-io-errors", true,
                Boolean::parseBoolean, null, builder.suppressConnectionIoErrors, true);
        this.suppressMalformedPacketErrorLevel = ServerPropertyImpl.create(source, "minestom.suppress-malformed-packet-error-level", 0,
                Integer::parseInt, range("minestom.suppress-malformed-packet-error-level", 0, Integer.MAX_VALUE), builder.suppressMalformedPacketErrorLevel, true);
        this.suppressMisusedPacketErrorLevel = ServerPropertyImpl.create(source, "minestom.suppress-misused-packet-error-level", 0,
                Integer::parseInt, range("minestom.suppress-misused-packet-error-level", 0, Integer.MAX_VALUE), builder.suppressMisusedPacketErrorLevel, true);
        this.rejectMalformedPacket = ServerPropertyImpl.create(source, "minestom.reject-malformed-packet", true,
                Boolean::parseBoolean, null, builder.rejectMalformedPacket, true);
        this.rejectMisusedPacket = ServerPropertyImpl.create(source, "minestom.reject-misused-packet", false,
                Boolean::parseBoolean, null, builder.rejectMisusedPacket, true);
        this.warnPacketUnreadBytes = ServerPropertyImpl.create(source, "minestom.warn-packet-unread-bytes", true,
                Boolean::parseBoolean, null, builder.warnPacketUnreadBytes, true);
        this.maxPacketSize = ServerPropertyImpl.create(source, "minestom.max-packet-size", 2_097_151,
                Integer::parseInt, range("minestom.max-packet-size", 1, 2_097_151), builder.maxPacketSize, false);
        this.maxPacketSizePreAuth = ServerPropertyImpl.create(source, "minestom.max-packet-size-pre-auth", 8_192,
                Integer::parseInt, range("minestom.max-packet-size-pre-auth", 1, 2_097_151), builder.maxPacketSizePreAuth, false);
        this.socketSendBufferSize = ServerPropertyImpl.create(source, "minestom.send-buffer-size", 262_143,
                Integer::parseInt, range("minestom.send-buffer-size", 1, Integer.MAX_VALUE), builder.socketSendBufferSize, true);
        this.socketReceiveBufferSize = ServerPropertyImpl.create(source, "minestom.receive-buffer-size", 32_767,
                Integer::parseInt, range("minestom.receive-buffer-size", 1, Integer.MAX_VALUE), builder.socketReceiveBufferSize, true);
        this.socketNoDelay = ServerPropertyImpl.create(source, "minestom.tcp-no-delay", true,
                Boolean::parseBoolean, null, builder.socketNoDelay, true);
        this.socketTimeout = ServerPropertyImpl.create(source, "minestom.socket-timeout", 15_000,
                Integer::parseInt, range("minestom.socket-timeout", 0, Integer.MAX_VALUE), builder.socketTimeout, true);
        this.pooledBufferSize = ServerPropertyImpl.create(source, "minestom.pooled-buffer-size", 16_383,
                Integer::parseInt, range("minestom.pooled-buffer-size", 1, Integer.MAX_VALUE), builder.pooledBufferSize, false);
        this.cachedPacket = ServerPropertyImpl.create(source, "minestom.cached-packet", true,
                Boolean::parseBoolean, null, builder.cachedPacket, true);
        this.fasterSocketWrites = ServerPropertyImpl.create(source, "minestom.new-socket-write-lock", false,
                Boolean::parseBoolean, null, builder.fasterSocketWrites, false);
        this.proxyProtocol = ServerPropertyImpl.create(source, "minestom.proxy-protocol", false,
                Boolean::parseBoolean, null, builder.proxyProtocol, false);
        this.proxyProtocolRequired = ServerPropertyImpl.create(source, "minestom.proxy-protocol.required", false,
                Boolean::parseBoolean, null, builder.proxyProtocolRequired, false);
        this.nbtMaxDepth = ServerPropertyImpl.create(source, "minestom.nbt.max-depth", 512,
                Integer::parseInt, range("minestom.nbt.max-depth", 1, Integer.MAX_VALUE), builder.nbtMaxDepth, false);
        this.nbtMaxBytes = ServerPropertyImpl.create(source, "minestom.nbt.max-bytes", 2_097_152,
                Integer::parseInt, range("minestom.nbt.max-bytes", 1, Integer.MAX_VALUE), builder.nbtMaxBytes, false);
    }

    private ServerProperties(ServerProperties source) {
        this.freezeRegistriesOnStart = ServerPropertyImpl.copy(source.freezeRegistriesOnStart);
        this.shutdownOnSignal = ServerPropertyImpl.copy(source.shutdownOnSignal);
        this.dispatcherThreads = ServerPropertyImpl.copy(source.dispatcherThreads);
        this.loginPluginMessageTimeout = ServerPropertyImpl.copy(source.loginPluginMessageTimeout);
        this.knownPacksResponseTimeout = ServerPropertyImpl.copy(source.knownPacksResponseTimeout);
        this.acceptTransfers = ServerPropertyImpl.copy(source.acceptTransfers);
        this.automaticComponentTranslation = ServerPropertyImpl.copy(source.automaticComponentTranslation);
        this.playerPacketPerTick = ServerPropertyImpl.copy(source.playerPacketPerTick);
        this.playerPacketQueueSize = ServerPropertyImpl.copy(source.playerPacketQueueSize);
        this.keepAliveDelay = ServerPropertyImpl.copy(source.keepAliveDelay);
        this.keepAliveKick = ServerPropertyImpl.copy(source.keepAliveKick);
        this.suppressConnectionAcceptErrors = ServerPropertyImpl.copy(source.suppressConnectionAcceptErrors);
        this.suppressConnectionIoErrors = ServerPropertyImpl.copy(source.suppressConnectionIoErrors);
        this.suppressMalformedPacketErrorLevel = ServerPropertyImpl.copy(source.suppressMalformedPacketErrorLevel);
        this.suppressMisusedPacketErrorLevel = ServerPropertyImpl.copy(source.suppressMisusedPacketErrorLevel);
        this.rejectMalformedPacket = ServerPropertyImpl.copy(source.rejectMalformedPacket);
        this.rejectMisusedPacket = ServerPropertyImpl.copy(source.rejectMisusedPacket);
        this.warnPacketUnreadBytes = ServerPropertyImpl.copy(source.warnPacketUnreadBytes);
        this.maxPacketSize = ServerPropertyImpl.copy(source.maxPacketSize);
        this.maxPacketSizePreAuth = ServerPropertyImpl.copy(source.maxPacketSizePreAuth);
        this.socketSendBufferSize = ServerPropertyImpl.copy(source.socketSendBufferSize);
        this.socketReceiveBufferSize = ServerPropertyImpl.copy(source.socketReceiveBufferSize);
        this.socketNoDelay = ServerPropertyImpl.copy(source.socketNoDelay);
        this.socketTimeout = ServerPropertyImpl.copy(source.socketTimeout);
        this.pooledBufferSize = ServerPropertyImpl.copy(source.pooledBufferSize);
        this.cachedPacket = ServerPropertyImpl.copy(source.cachedPacket);
        this.fasterSocketWrites = ServerPropertyImpl.copy(source.fasterSocketWrites);
        this.proxyProtocol = ServerPropertyImpl.copy(source.proxyProtocol);
        this.proxyProtocolRequired = ServerPropertyImpl.copy(source.proxyProtocolRequired);
        this.nbtMaxDepth = ServerPropertyImpl.copy(source.nbtMaxDepth);
        this.nbtMaxBytes = ServerPropertyImpl.copy(source.nbtMaxBytes);
    }

    /** Resolves a fresh configuration from the current system properties. */
    public static ServerProperties fromSystemProperties() {
        return builder().build();
    }

    /** Unspecified values are read from system properties when {@link Builder#build()} is called. */
    public static Builder builder() {
        return new Builder(null);
    }

    /** Uses a copy of the supplied property source instead of JVM system properties. */
    public static Builder builder(Properties source) {
        return new Builder(snapshot(Objects.requireNonNull(source)));
    }

    /** Copies current values and writability without resolving system properties again. */
    public ServerProperties copy() {
        return new ServerProperties(this);
    }

    private static Properties snapshot(Properties source) {
        Properties result = new Properties();
        synchronized (source) {
            for (String name : source.stringPropertyNames()) {
                result.setProperty(name, source.getProperty(name));
            }
        }
        return result;
    }

    /**
     * Whether startup freezes dynamic registry entries. Always immutable; tags remain mutable.
     * Defaults to {@code minestom.registry.freeze-on-start}, or otherwise to false when either
     * {@code minestom.inside-test} or {@code minestom.registry.unsafe-ops} is true.
     * Explicit overrides win over all these defaults. Disabling this does not synchronize entry
     * changes with connected clients or application-held packet caches. Explicit registry freeze
     * calls always take effect.
     */
    public ServerProperty<Boolean> freezeRegistriesOnStart() {
        return freezeRegistriesOnStart;
    }

    /** Registers a shutdown hook when starting this process. Always immutable. */
    public ServerProperty<Boolean> shutdownOnSignal() {
        return shutdownOnSignal;
    }

    /** Dispatcher thread count, fixed when the process is created. */
    public ServerProperty<Integer> dispatcherThreads() {
        return dispatcherThreads;
    }

    /** Timeout in milliseconds for login plugin replies. */
    public ServerProperty<Long> loginPluginMessageTimeout() {
        return loginPluginMessageTimeout;
    }

    /** Timeout in milliseconds for the known-packs reply. */
    public ServerProperty<Long> knownPacksResponseTimeout() {
        return knownPacksResponseTimeout;
    }

    public ServerProperty<Boolean> acceptTransfers() {
        return acceptTransfers;
    }

    public ServerProperty<Boolean> automaticComponentTranslation() {
        return automaticComponentTranslation;
    }

    public ServerProperty<Integer> playerPacketPerTick() {
        return playerPacketPerTick;
    }

    /** Capacity of each player's incoming packet queue. Always immutable. */
    public ServerProperty<Integer> playerPacketQueueSize() {
        return playerPacketQueueSize;
    }

    /** Delay in milliseconds before sending another keepalive. */
    public ServerProperty<Long> keepAliveDelay() {
        return keepAliveDelay;
    }

    /** Timeout in milliseconds before disconnecting a player that has not answered. */
    public ServerProperty<Long> keepAliveKick() {
        return keepAliveKick;
    }

    public ServerProperty<Boolean> suppressConnectionAcceptErrors() {
        return suppressConnectionAcceptErrors;
    }

    public ServerProperty<Boolean> suppressConnectionIoErrors() {
        return suppressConnectionIoErrors;
    }

    public ServerProperty<Integer> suppressMalformedPacketErrorLevel() {
        return suppressMalformedPacketErrorLevel;
    }

    public ServerProperty<Integer> suppressMisusedPacketErrorLevel() {
        return suppressMisusedPacketErrorLevel;
    }

    public ServerProperty<Boolean> rejectMalformedPacket() {
        return rejectMalformedPacket;
    }

    public ServerProperty<Boolean> rejectMisusedPacket() {
        return rejectMisusedPacket;
    }

    public ServerProperty<Boolean> warnPacketUnreadBytes() {
        return warnPacketUnreadBytes;
    }

    /** Maximum packet size in bytes, including the packet ID. Always immutable. */
    public ServerProperty<Integer> maxPacketSize() {
        return maxPacketSize;
    }

    /** Maximum incoming packet size during handshake, status and login. Always immutable. */
    public ServerProperty<Integer> maxPacketSizePreAuth() {
        return maxPacketSizePreAuth;
    }

    /** Send buffer size applied to subsequently accepted sockets. */
    public ServerProperty<Integer> socketSendBufferSize() {
        return socketSendBufferSize;
    }

    /** Receive buffer size applied to subsequently accepted sockets. */
    public ServerProperty<Integer> socketReceiveBufferSize() {
        return socketReceiveBufferSize;
    }

    /** TCP no-delay option applied to subsequently accepted sockets. */
    public ServerProperty<Boolean> socketNoDelay() {
        return socketNoDelay;
    }

    /** Timeout in milliseconds applied to subsequently accepted sockets. */
    public ServerProperty<Integer> socketTimeout() {
        return socketTimeout;
    }

    /** Initial capacity of process packet buffers. Always immutable. */
    public ServerProperty<Integer> pooledBufferSize() {
        return pooledBufferSize;
    }

    public ServerProperty<Boolean> cachedPacket() {
        return cachedPacket;
    }

    /** Selects the socket writer strategy. Always immutable. */
    public ServerProperty<Boolean> fasterSocketWrites() {
        return fasterSocketWrites;
    }

    /** Enables PROXY protocol detection on incoming connections. Always immutable. */
    public ServerProperty<Boolean> proxyProtocol() {
        return proxyProtocol;
    }

    /** Rejects connections without PROXY headers when detection is enabled. Always immutable. */
    public ServerProperty<Boolean> proxyProtocolRequired() {
        return proxyProtocolRequired;
    }

    /** Maximum nested NBT container depth. Always immutable; raising this risks stack overflow. */
    public ServerProperty<Integer> nbtMaxDepth() {
        return nbtMaxDepth;
    }

    /** Approximate decoded heap budget for untrusted NBT, not encoded bytes. Always immutable. */
    public ServerProperty<Integer> nbtMaxBytes() {
        return nbtMaxBytes;
    }

    public static final class Builder {
        private final @Nullable Properties source;
        private @Nullable Boolean freezeRegistriesOnStart;
        private @Nullable Boolean shutdownOnSignal;
        private @Nullable Integer dispatcherThreads;
        private @Nullable Long loginPluginMessageTimeout;
        private @Nullable Long knownPacksResponseTimeout;
        private @Nullable Boolean acceptTransfers;
        private @Nullable Boolean automaticComponentTranslation;
        private @Nullable Integer playerPacketPerTick;
        private @Nullable Integer playerPacketQueueSize;
        private @Nullable Long keepAliveDelay;
        private @Nullable Long keepAliveKick;
        private @Nullable Boolean suppressConnectionAcceptErrors;
        private @Nullable Boolean suppressConnectionIoErrors;
        private @Nullable Integer suppressMalformedPacketErrorLevel;
        private @Nullable Integer suppressMisusedPacketErrorLevel;
        private @Nullable Boolean rejectMalformedPacket;
        private @Nullable Boolean rejectMisusedPacket;
        private @Nullable Boolean warnPacketUnreadBytes;
        private @Nullable Integer maxPacketSize;
        private @Nullable Integer maxPacketSizePreAuth;
        private @Nullable Integer socketSendBufferSize;
        private @Nullable Integer socketReceiveBufferSize;
        private @Nullable Boolean socketNoDelay;
        private @Nullable Integer socketTimeout;
        private @Nullable Integer pooledBufferSize;
        private @Nullable Boolean cachedPacket;
        private @Nullable Boolean fasterSocketWrites;
        private @Nullable Boolean proxyProtocol;
        private @Nullable Boolean proxyProtocolRequired;
        private @Nullable Integer nbtMaxDepth;
        private @Nullable Integer nbtMaxBytes;

        private Builder(@Nullable Properties source) {
            this.source = source;
        }

        public Builder freezeRegistriesOnStart(boolean value) {
            this.freezeRegistriesOnStart = value;
            return this;
        }

        public Builder shutdownOnSignal(boolean value) {
            this.shutdownOnSignal = value;
            return this;
        }

        public Builder dispatcherThreads(int value) {
            this.dispatcherThreads = value;
            return this;
        }

        public Builder loginPluginMessageTimeout(long value) {
            this.loginPluginMessageTimeout = value;
            return this;
        }

        public Builder knownPacksResponseTimeout(long value) {
            this.knownPacksResponseTimeout = value;
            return this;
        }

        public Builder acceptTransfers(boolean value) {
            this.acceptTransfers = value;
            return this;
        }

        public Builder automaticComponentTranslation(boolean value) {
            this.automaticComponentTranslation = value;
            return this;
        }

        public Builder playerPacketPerTick(int value) {
            this.playerPacketPerTick = value;
            return this;
        }

        public Builder playerPacketQueueSize(int value) {
            this.playerPacketQueueSize = value;
            return this;
        }

        public Builder keepAliveDelay(long value) {
            this.keepAliveDelay = value;
            return this;
        }

        public Builder keepAliveKick(long value) {
            this.keepAliveKick = value;
            return this;
        }

        public Builder suppressConnectionAcceptErrors(boolean value) {
            this.suppressConnectionAcceptErrors = value;
            return this;
        }

        public Builder suppressConnectionIoErrors(boolean value) {
            this.suppressConnectionIoErrors = value;
            return this;
        }

        public Builder suppressMalformedPacketErrorLevel(int value) {
            this.suppressMalformedPacketErrorLevel = value;
            return this;
        }

        public Builder suppressMisusedPacketErrorLevel(int value) {
            this.suppressMisusedPacketErrorLevel = value;
            return this;
        }

        public Builder rejectMalformedPacket(boolean value) {
            this.rejectMalformedPacket = value;
            return this;
        }

        public Builder rejectMisusedPacket(boolean value) {
            this.rejectMisusedPacket = value;
            return this;
        }

        public Builder warnPacketUnreadBytes(boolean value) {
            this.warnPacketUnreadBytes = value;
            return this;
        }

        public Builder maxPacketSize(int value) {
            this.maxPacketSize = value;
            return this;
        }

        public Builder maxPacketSizePreAuth(int value) {
            this.maxPacketSizePreAuth = value;
            return this;
        }

        public Builder socketSendBufferSize(int value) {
            this.socketSendBufferSize = value;
            return this;
        }

        public Builder socketReceiveBufferSize(int value) {
            this.socketReceiveBufferSize = value;
            return this;
        }

        public Builder socketNoDelay(boolean value) {
            this.socketNoDelay = value;
            return this;
        }

        public Builder socketTimeout(int value) {
            this.socketTimeout = value;
            return this;
        }

        public Builder pooledBufferSize(int value) {
            this.pooledBufferSize = value;
            return this;
        }

        public Builder cachedPacket(boolean value) {
            this.cachedPacket = value;
            return this;
        }

        public Builder fasterSocketWrites(boolean value) {
            this.fasterSocketWrites = value;
            return this;
        }

        public Builder proxyProtocol(boolean value) {
            this.proxyProtocol = value;
            return this;
        }

        public Builder proxyProtocolRequired(boolean value) {
            this.proxyProtocolRequired = value;
            return this;
        }

        public Builder nbtMaxDepth(int value) {
            this.nbtMaxDepth = value;
            return this;
        }

        public Builder nbtMaxBytes(int value) {
            this.nbtMaxBytes = value;
            return this;
        }

        public ServerProperties build() {
            return new ServerProperties(this, source != null ? source : snapshot(System.getProperties()));
        }
    }

    static ServerProperty<Boolean> Boolean(String name, boolean defaultValue) {
        return ServerPropertyImpl.create(name, defaultValue, Boolean::parseBoolean);
    }

    static ServerProperty<Byte> Byte(String name, byte defaultValue) {
        return ServerPropertyImpl.create(name, defaultValue, Byte::parseByte);
    }

    static ServerProperty<Byte> Byte(String name, byte defaultValue, byte minValue, byte maxValue) {
        return ServerPropertyImpl.create(name, defaultValue, Byte::parseByte, range(name, minValue, maxValue));
    }

    static ServerProperty<Short> Short(String name, short defaultValue) {
        return ServerPropertyImpl.create(name, defaultValue, Short::parseShort);
    }

    static ServerProperty<Short> Short(String name, short defaultValue, short minValue, short maxValue) {
        return ServerPropertyImpl.create(name, defaultValue, Short::parseShort, range(name, minValue, maxValue));
    }

    static ServerProperty<Integer> Integer(String name, int defaultValue) {
        return ServerPropertyImpl.create(name, defaultValue, Integer::parseInt);
    }

    static ServerProperty<Integer> Integer(String name, int defaultValue, int minValue, int maxValue) {
        return ServerPropertyImpl.create(name, defaultValue, Integer::parseInt, range(name, minValue, maxValue));
    }

    static ServerProperty<Long> Long(String name, long defaultValue) {
        return ServerPropertyImpl.create(name, defaultValue, Long::parseLong);
    }

    static ServerProperty<Long> Long(String name, long defaultValue, long minValue, long maxValue) {
        return ServerPropertyImpl.create(name, defaultValue, Long::parseLong, range(name, minValue, maxValue));
    }

    static ServerProperty<Float> Float(String name, float defaultValue) {
        return ServerPropertyImpl.create(name, defaultValue, Float::parseFloat);
    }

    static ServerProperty<Float> Float(String name, float defaultValue, float minValue, float maxValue) {
        return ServerPropertyImpl.create(name, defaultValue, Float::parseFloat, range(name, minValue, maxValue));
    }

    static ServerProperty<Double> Double(String name, double defaultValue) {
        return ServerPropertyImpl.create(name, defaultValue, Double::parseDouble);
    }

    static ServerProperty<Double> Double(String name, double defaultValue, double minValue, double maxValue) {
        return ServerPropertyImpl.create(name, defaultValue, Double::parseDouble, range(name, minValue, maxValue));
    }

    static ServerProperty<String> String(String name, String defaultValue) {
        return ServerPropertyImpl.create(name, defaultValue, Function.identity());
    }

    private static <T extends Comparable<T>> Consumer<T> range(String name, T minValue, T maxValue) {
        return value -> {
            if (value.compareTo(minValue) < 0 || value.compareTo(maxValue) > 0) {
                throw new IllegalArgumentException(String.format(
                        "Property '%s' value must be in range [%s..%s] but was %s",
                        name, minValue, maxValue, value
                ));
            }
        };
    }
}
