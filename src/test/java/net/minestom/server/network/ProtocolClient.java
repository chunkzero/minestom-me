package net.minestom.server.network;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerProcess;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.client.common.ClientSettingsPacket;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.server.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.configuration.SelectKnownPacksPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.network.player.ClientSettings;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A blocking protocol client using real loopback sockets, with bounded reads. */
final class ProtocolClient implements AutoCloseable {
    private final ServerProcess process;
    private final Socket socket = new Socket();
    private ConnectionState sending = ConnectionState.HANDSHAKE;
    private ConnectionState receiving = ConnectionState.LOGIN;
    private int compression;

    ProtocolClient(ServerProcess process) throws IOException {
        this.process = process;
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()), 5000);
        socket.setSoTimeout(5000);
    }

    void login(String username, UUID uuid) throws IOException {
        handshake("localhost", ClientHandshakePacket.Intent.LOGIN);
        send(new ClientLoginStartPacket(username, uuid));
    }

    void handshake(String address, ClientHandshakePacket.Intent intent) throws IOException {
        send(new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION, address, process.server().getPort(), intent));
    }

    void send(ClientPacket packet) throws IOException {
        var buffer = NetworkBuffer.resizableBuffer(process.registries());
        PacketWriting.writeFramedPacket(buffer, sending, packet, compression);
        byte[] bytes = new byte[(int) buffer.writeIndex()];
        buffer.copyTo(0, bytes, 0, bytes.length);
        socket.getOutputStream().write(bytes);
        sending = PacketVanilla.nextClientState(packet, sending);
    }

    ServerPacket read() throws Exception {
        int length = 0;
        for (int shift = 0; ; shift += 7) {
            if (shift >= 35) throw new IOException("Invalid frame length");
            int value = socket.getInputStream().read();
            if (value == -1) throw new EOFException();
            length |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) break;
        }
        byte[] body = socket.getInputStream().readNBytes(length);
        if (body.length != length) throw new EOFException();
        var buffer = NetworkBuffer.resizableBuffer(process.registries());
        buffer.write(NetworkBuffer.VAR_INT, length);
        buffer.write(NetworkBuffer.RAW_BYTES, body);
        var result = assertInstanceOf(PacketReading.Result.Success.class,
                PacketReading.readServer(buffer, receiving, compression > 0));
        assertEquals(1, result.packets().size());
        assertEquals(0, buffer.readableBytes());
        var packet = (ServerPacket) ((PacketReading.ParsedPacket<?>) result.packets().getFirst()).packet();
        receiving = PacketVanilla.nextServerState(packet, receiving);
        if (packet instanceof SetCompressionPacket setCompression) compression = setCompression.threshold();
        if (packet instanceof KeepAlivePacket keepAlive) send(new ClientKeepAlivePacket(keepAlive.id()));
        return packet;
    }

    <T extends ServerPacket> List<ServerPacket> readThrough(Class<T> type) throws Exception {
        var packets = new ArrayList<ServerPacket>();
        do {
            packets.add(read());
        } while (!type.isInstance(packets.getLast()));
        return packets;
    }

    List<ServerPacket> finishLogin(ClientSettings settings) throws Exception {
        var packets = readThrough(LoginSuccessPacket.class);
        send(new ClientLoginAcknowledgedPacket());
        packets.addAll(configure(settings));
        return packets;
    }

    List<ServerPacket> configure(ClientSettings settings) throws Exception {
        send(new ClientSettingsPacket(settings));
        var packets = new ArrayList<ServerPacket>();
        while (true) {
            var packet = read();
            packets.add(packet);
            if (packet instanceof SelectKnownPacksPacket) send(new ClientSelectKnownPacksPacket(List.of()));
            if (packet instanceof FinishConfigurationPacket) break;
        }
        send(new ClientFinishConfigurationPacket());
        return packets;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
