package net.minestom.server.ping;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.extras.lan.OpenToLAN;

import java.util.function.BiFunction;

/**
 * An enum containing the different types of server list ping responses.
 *
 * @see <a href="https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Server_List_Ping">the Minecraft Wiki</a>
 * @see ServerListPingEvent
 */
public enum ServerListPingType {
    /**
     * The client is on version 1.16 or higher and supports full RGB with JSON text formatting.
     */
    MODERN_FULL_RGB((data, _) -> getModernPingResponse(data, true).toString()),

    /**
     * The client is on version 1.7 or higher and doesn't support full RGB but does support JSON text formatting.
     */
    MODERN_NAMED_COLORS((data, _) -> getModernPingResponse(data, false).toString()),

    /**
     * The client is on version 1.4 or higher and supports a description, the player count and the version information.
     */
    LEGACY_VERSIONED((data, _) -> getLegacyPingResponse(data, true)),

    /**
     * The client is on version 1.3.2 or lower and supports a description and the player count.
     */
    LEGACY_UNVERSIONED((data, _) -> getLegacyPingResponse(data, false)),

    /**
     * The ping that is sent when {@link OpenToLAN} is enabled and sending packets.
     * Only the description formatted as a legacy string is sent.
     * Ping events with this ping version are <b>not</b> cancellable.
     */
    OPEN_TO_LAN(ServerListPingType::getOpenToLANPing);

    private final BiFunction<Status, Integer, String> pingResponseCreator;

    ServerListPingType(BiFunction<Status, Integer, String> pingResponseCreator) {
        this.pingResponseCreator = pingResponseCreator;
    }

    /**
     * Gets the ping response for this version.
     * Use {@link #getPingResponse(Status, int)} for {@link #OPEN_TO_LAN}, supplying the owning server's port.
     *
     * @param status the response data
     * @return the response
     * @throws IllegalStateException if this is {@link #OPEN_TO_LAN} and no port was supplied
     */
    public String getPingResponse(Status status) {
        if (this == OPEN_TO_LAN) throw new IllegalStateException("LAN responses require the server port");
        return getPingResponse(status, 0);
    }

    /** Creates a response with the owning server's explicit listening port. */
    public String getPingResponse(Status status, int port) {
        return this.pingResponseCreator.apply(status, port);
    }

    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    /**
     * Creates a ping sent when the server is sending {@link OpenToLAN} packets.
     *
     * @param status the response data
     * @return the ping
     * @see OpenToLAN
     */
    public static String getOpenToLANPing(Status status, int port) {
        return String.format("[MOTD]%s[/MOTD][AD]%s[/AD]", SECTION.serialize(status.description()), port);
    }

    /**
     * Creates a legacy ping response for client versions below the Netty rewrite (1.6-).
     *
     * @param status           the response data
     * @param supportsVersions if the client supports recieving the versions of the server
     * @return the response
     */
    public static String getLegacyPingResponse(Status status, boolean supportsVersions) {
        final String motd = SECTION.serialize(status.description());
        Status.PlayerInfo playerInfo = status.playerInfo();
        int onlinePlayers = playerInfo == null ? 0 : playerInfo.onlinePlayers();
        int maxPlayers = playerInfo == null ? 1 : playerInfo.maxPlayers();

        if (supportsVersions) {
            return String.format("§1\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d",
                    status.versionInfo().protocolVersion(),
                    status.versionInfo().name(),
                    motd,
                    onlinePlayers,
                    maxPlayers);
        } else {
            return String.format("%s§%d§%d", motd, onlinePlayers, maxPlayers);
        }
    }

    /**
     * Creates a modern ping response for client versions above the Netty rewrite (1.7+).
     *
     * @param status          the response data
     * @param supportsFullRgb if the client supports full RGB colors in text components
     * @return the response
     */
    public static JsonObject getModernPingResponse(Status status, boolean supportsFullRgb) {
        JsonObject element = (JsonObject) Status.CODEC.encode(Transcoder.JSON, status).orElseThrow();

        // reset description element with downscaled colors if this version does not support RGB
        if (!supportsFullRgb) {
            GsonComponentSerializer serializer = GsonComponentSerializer.colorDownsamplingGson();
            element.add("description", serializer.serializeToTree(status.description()));
        }

        return element;
    }

    /**
     * Gets the server list ping version from the protocol version.
     * This only works for modern ping responses since the Netty rewrite.
     *
     * @param version the protocol version
     * @return the corresponding server list ping version
     */
    public static ServerListPingType fromModernProtocolVersion(int version) {
        return version >= 713 ? MODERN_FULL_RGB : MODERN_NAMED_COLORS;
    }
}
