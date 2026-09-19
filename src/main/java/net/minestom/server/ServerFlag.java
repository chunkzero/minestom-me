package net.minestom.server;

import net.minestom.server.property.ServerProperties;
import net.minestom.server.property.ServerProperty;

/**
 * Contains server settings/flags to be set with system properties.
 *
 * <p>Some flags (labeled at the bottom) are experimental. They may be removed without notice, and may have issues.</p>
 *
 * @deprecated use {@link ServerProperties}, which names the same settings and reads them through
 * {@link ServerProperty#get()}. Every constant here is the value its
 * property held when this class was initialized, so a setting made writable with
 * {@code <name>.mutable=true} never reaches a reader that came through this class.
 */
@Deprecated(forRemoval = true)
public final class ServerFlag {
    private static final ServerProperties DEFAULT_PROPERTIES = ServerProperties.defaults();

    // Server Behavior
    public static final boolean SHUTDOWN_ON_SIGNAL = DEFAULT_PROPERTIES.shutdownOnSignal().get();
    public static final int SERVER_TICKS_PER_SECOND = ServerProperties.SERVER_TICKS_PER_SECOND.get();
    public static final int SERVER_MAX_TICK_CATCH_UP = ServerProperties.SERVER_MAX_TICK_CATCH_UP.get();
    public static final int CHUNK_VIEW_DISTANCE = ServerProperties.CHUNK_VIEW_DISTANCE.get();
    public static final int ENTITY_VIEW_DISTANCE = ServerProperties.ENTITY_VIEW_DISTANCE.get();
    public static final int ENTITY_SYNCHRONIZATION_TICKS = ServerProperties.ENTITY_SYNCHRONIZATION_TICKS.get();
    public static final int DISPATCHER_THREADS = DEFAULT_PROPERTIES.dispatcherThreads().get();
    public static final int SEND_LIGHT_AFTER_BLOCK_PLACEMENT_DELAY = ServerProperties.SEND_LIGHT_AFTER_BLOCK_PLACEMENT_DELAY.get();
    public static final long LOGIN_PLUGIN_MESSAGE_TIMEOUT = DEFAULT_PROPERTIES.loginPluginMessageTimeout().get();
    public static final long KNOWN_PACKS_RESPONSE_TIMEOUT = DEFAULT_PROPERTIES.knownPacksResponseTimeout().get();
    public static final boolean ACCEPT_TRANSFERS = DEFAULT_PROPERTIES.acceptTransfers().get();
    public static final boolean AUTOMATIC_COMPONENT_TRANSLATION = DEFAULT_PROPERTIES.automaticComponentTranslation().get();

    // Network rate limiting
    public static final int PLAYER_PACKET_PER_TICK = DEFAULT_PROPERTIES.playerPacketPerTick().get();
    public static final int PLAYER_PACKET_QUEUE_SIZE = DEFAULT_PROPERTIES.playerPacketQueueSize().get();
    public static final long KEEP_ALIVE_DELAY = DEFAULT_PROPERTIES.keepAliveDelay().get();
    public static final long KEEP_ALIVE_KICK = DEFAULT_PROPERTIES.keepAliveKick().get();
    public static final int PLAYER_CHUNK_UPDATE_LIMITER_HISTORY_SIZE = ServerProperties.PLAYER_CHUNK_UPDATE_LIMITER_HISTORY_SIZE.get();

    // Network error handling
    public static final boolean SUPPRESS_CONNECTION_ACCEPT_ERRORS = DEFAULT_PROPERTIES.suppressConnectionAcceptErrors().get();
    public static final boolean SUPPRESS_CONNECTION_IO_ERRORS = DEFAULT_PROPERTIES.suppressConnectionIoErrors().get();
    public static final int SUPPRESS_MALFORMED_PACKET_ERROR_LEVEL = DEFAULT_PROPERTIES.suppressMalformedPacketErrorLevel().get();
    public static final int SUPPRESS_MISUSED_PACKET_ERROR_LEVEL = DEFAULT_PROPERTIES.suppressMisusedPacketErrorLevel().get();
    public static final boolean REJECT_MALFORMED_PACKET = DEFAULT_PROPERTIES.rejectMalformedPacket().get();
    public static final boolean REJECT_MISUSED_PACKET = DEFAULT_PROPERTIES.rejectMisusedPacket().get();
    public static final boolean WARN_PACKET_UNREAD_BYTES = DEFAULT_PROPERTIES.warnPacketUnreadBytes().get();

    // Network buffers
    public static final int MAX_PACKET_SIZE = DEFAULT_PROPERTIES.maxPacketSize().get();
    public static final int MAX_PACKET_SIZE_PRE_AUTH = DEFAULT_PROPERTIES.maxPacketSizePreAuth().get();
    public static final int SOCKET_SEND_BUFFER_SIZE = DEFAULT_PROPERTIES.socketSendBufferSize().get();
    public static final int SOCKET_RECEIVE_BUFFER_SIZE = DEFAULT_PROPERTIES.socketReceiveBufferSize().get();
    public static final boolean SOCKET_NO_DELAY = DEFAULT_PROPERTIES.socketNoDelay().get();
    public static final int SOCKET_TIMEOUT = DEFAULT_PROPERTIES.socketTimeout().get();
    public static final int POOLED_BUFFER_SIZE = DEFAULT_PROPERTIES.pooledBufferSize().get();

    // Chunk update
    public static final float MIN_CHUNKS_PER_TICK = ServerProperties.MIN_CHUNKS_PER_TICK.get();
    public static final float MAX_CHUNKS_PER_TICK = ServerProperties.MAX_CHUNKS_PER_TICK.get();
    public static final float CHUNKS_PER_TICK_MULTIPLIER = ServerProperties.CHUNKS_PER_TICK_MULTIPLIER.get();

    // Packet sending optimizations
    public static final boolean GROUPED_PACKET = ServerProperties.GROUPED_PACKET.get();
    public static final boolean CACHED_PACKET = DEFAULT_PROPERTIES.cachedPacket().get();
    public static final boolean VIEWABLE_PACKET = ServerProperties.VIEWABLE_PACKET.get();

    // Tags
    public static final boolean TAG_HANDLER_CACHE_ENABLED = ServerProperties.TAG_HANDLER_CACHE_ENABLED.get();
    public static final boolean SERIALIZE_EMPTY_COMPOUND = ServerProperties.SERIALIZE_EMPTY_COMPOUND.get();

    // Online Mode
    public static final String AUTH_URL = ServerProperties.AUTH_URL.get();
    public static final boolean AUTH_PREVENT_PROXY_CONNECTIONS = ServerProperties.AUTH_PREVENT_PROXY_CONNECTIONS.get();

    // World
    public static final int WORLD_BORDER_SIZE = ServerProperties.WORLD_BORDER_SIZE.get();

    // Entities
    public static final boolean ENFORCE_INTERACTION_LIMIT = ServerProperties.ENFORCE_INTERACTION_LIMIT.get();

    // Experimental/Unstable
    public static final boolean REGISTRY_UNSAFE_OPS = ServerProperties.REGISTRY_UNSAFE_OPS.get();
    public static final boolean FASTER_SOCKET_WRITES = DEFAULT_PROPERTIES.fasterSocketWrites().get();
    public static final boolean ACQUIRABLE_STRICT = ServerProperties.ACQUIRABLE_STRICT.get();
    public static final boolean UNSAFE_COLLECTIONS = ServerProperties.UNSAFE_COLLECTIONS.get();
    public static final boolean TEMPLATE_COMPILER = ServerProperties.TEMPLATE_COMPILER.get();
    public static final boolean PROXY_PROTOCOL = DEFAULT_PROPERTIES.proxyProtocol().get();
    public static final boolean PROXY_PROTOCOL_REQUIRED = DEFAULT_PROPERTIES.proxyProtocolRequired().get();
    public static final int NBT_MAX_DEPTH = DEFAULT_PROPERTIES.nbtMaxDepth().get();
    public static final int NBT_MAX_BYTES = DEFAULT_PROPERTIES.nbtMaxBytes().get();

    public static final boolean INSIDE_TEST = ServerProperties.INSIDE_TEST.get();

    private ServerFlag() {}
}
