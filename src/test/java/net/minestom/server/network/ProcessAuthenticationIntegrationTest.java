package net.minestom.server.network;

import net.minestom.server.Auth;
import net.minestom.server.ServerProcess;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientEncryptionResponsePacket;
import net.minestom.server.network.packet.client.login.ClientLoginPluginResponsePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.server.login.EncryptionRequestPacket;
import net.minestom.server.network.packet.server.login.LoginDisconnectPacket;
import net.minestom.server.network.packet.server.login.LoginPluginRequestPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessAuthenticationIntegrationTest {
    @Test
    void onlineAndOfflineProcessesChooseTheirOwnLoginFlow() throws Exception {
        try (var pair = new ServerProcessPair(new Auth.Online(), new Auth.Offline())) {
            start(pair.first());
            start(pair.second());
            try (var online = new ProtocolClient(pair.first()); var offline = new ProtocolClient(pair.second())) {
                UUID identity = UUID.randomUUID();
                online.login("SameName", identity);
                offline.login("SameName", identity);
                assertInstanceOf(EncryptionRequestPacket.class, online.read());
                assertEquals(identity, assertInstanceOf(LoginSuccessPacket.class, offline.read()).gameProfile().uuid());
            }
        }
    }

    @Test
    void onlineChallengesUseIndependentKeysAndVerifyTheirOwnNonce() throws Exception {
        var firstAuth = new Auth.Online();
        var secondAuth = new Auth.Online();
        try (var pair = new ServerProcessPair(firstAuth, secondAuth)) {
            var firstErrors = new LinkedBlockingQueue<Throwable>();
            var secondErrors = new LinkedBlockingQueue<Throwable>();
            pair.first().exception().setExceptionHandler(firstErrors::add);
            pair.second().exception().setExceptionHandler(secondErrors::add);
            start(pair.first());
            start(pair.second());
            try (var first = new ProtocolClient(pair.first()); var second = new ProtocolClient(pair.second())) {
                first.login("SameName", UUID.randomUUID());
                second.login("SameName", UUID.randomUUID());
                var firstRequest = assertInstanceOf(EncryptionRequestPacket.class, first.read());
                var secondRequest = assertInstanceOf(EncryptionRequestPacket.class, second.read());
                assertArrayEquals(firstAuth.keyPair().getPublic().getEncoded(), firstRequest.publicKey());
                assertArrayEquals(secondAuth.keyPair().getPublic().getEncoded(), secondRequest.publicKey());
                var cipher = Cipher.getInstance("RSA");
                cipher.init(Cipher.ENCRYPT_MODE, secondAuth.keyPair().getPublic());
                byte[] wrongNonce = secondRequest.verifyToken().clone();
                wrongNonce[0] ^= 1;
                second.send(new ClientEncryptionResponsePacket(new byte[0], cipher.doFinal(wrongNonce)));
                assertInstanceOf(LoginDisconnectPacket.class, second.read());
                // First's challenge is still usable after a failed login to second.
                cipher.init(Cipher.ENCRYPT_MODE, firstAuth.keyPair().getPublic());
                wrongNonce = firstRequest.verifyToken().clone();
                wrongNonce[0] ^= 1;
                first.send(new ClientEncryptionResponsePacket(new byte[0], cipher.doFinal(wrongNonce)));
                assertInstanceOf(LoginDisconnectPacket.class, first.read());
            }
            try (var malformed = new ProtocolClient(pair.second())) {
                malformed.login("Malformed", UUID.randomUUID());
                assertInstanceOf(EncryptionRequestPacket.class, malformed.read());
                malformed.send(new ClientEncryptionResponsePacket(new byte[0], new byte[]{1}));
                assertInstanceOf(LoginDisconnectPacket.class, malformed.read());
                assertInstanceOf(IllegalStateException.class, secondErrors.poll(5, TimeUnit.SECONDS));
                assertTrue(firstErrors.isEmpty());
            }
        }
    }

    @Test
    void velocitySecretsProfilesAndCallbackFailuresStayWithTheirProcess() throws Exception {
        var firstAuth = new Auth.Velocity("first secret");
        var secondAuth = new Auth.Velocity("second secret");
        try (var pair = new ServerProcessPair(firstAuth, secondAuth)) {
            var firstErrors = new LinkedBlockingQueue<Throwable>();
            var secondErrors = new LinkedBlockingQueue<Throwable>();
            var firstLogins = new LinkedBlockingQueue<GameProfile>();
            var secondLogins = new LinkedBlockingQueue<GameProfile>();
            pair.first().exception().setExceptionHandler(firstErrors::add);
            pair.second().exception().setExceptionHandler(secondErrors::add);
            pair.first().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> firstLogins.add(event.getGameProfile()));
            pair.second().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> secondLogins.add(event.getGameProfile()));
            start(pair.first());
            start(pair.second());
            var firstProfile = new GameProfile(UUID.randomUUID(), "First", List.of(new GameProfile.Property("textures", "first", "sig")));
            var secondProfile = new GameProfile(UUID.randomUUID(), "Second", List.of(new GameProfile.Property("textures", "second", "sig")));
            try (var first = new ProtocolClient(pair.first()); var second = new ProtocolClient(pair.second())) {
                first.login("First", UUID.randomUUID());
                second.login("Second", UUID.randomUUID());
                var firstRequest = assertInstanceOf(LoginPluginRequestPacket.class, first.read());
                var secondRequest = assertInstanceOf(LoginPluginRequestPacket.class, second.read());
                assertEquals(Auth.Velocity.PLAYER_INFO_CHANNEL, firstRequest.channel());
                assertEquals(firstRequest.messageId(), secondRequest.messageId());
                first.send(new ClientLoginPluginResponsePacket(firstRequest.messageId(), signedProfile(firstAuth, firstProfile)));
                // A signature valid for A must not authenticate a client on B.
                second.send(new ClientLoginPluginResponsePacket(secondRequest.messageId(), signedProfile(firstAuth, secondProfile)));
                assertEquals(firstProfile, assertInstanceOf(LoginSuccessPacket.class, first.read()).gameProfile());
                assertInstanceOf(LoginDisconnectPacket.class, second.read());
                assertEquals(firstProfile, firstLogins.poll(5, TimeUnit.SECONDS));
                assertTrue(secondLogins.isEmpty());
            }
            try (var valid = new ProtocolClient(pair.second())) {
                valid.login("Second", UUID.randomUUID());
                var request = assertInstanceOf(LoginPluginRequestPacket.class, valid.read());
                valid.send(new ClientLoginPluginResponsePacket(request.messageId(), signedProfile(secondAuth, secondProfile)));
                assertEquals(secondProfile, assertInstanceOf(LoginSuccessPacket.class, valid.read()).gameProfile());
                assertEquals(secondProfile, secondLogins.poll(5, TimeUnit.SECONDS));
            }
            try (var malformed = new ProtocolClient(pair.second())) {
                malformed.login("Malformed", UUID.randomUUID());
                var request = assertInstanceOf(LoginPluginRequestPacket.class, malformed.read());
                malformed.send(new ClientLoginPluginResponsePacket(request.messageId(), new byte[]{1}));
                assertInstanceOf(LoginDisconnectPacket.class, malformed.read());
                assertNotNull(secondErrors.poll(5, TimeUnit.SECONDS));
                assertTrue(firstErrors.isEmpty());
            }
        }
    }

    @Test
    void bungeeHandshakeLimitsAndGuardTokensUseTheDestinationAuth() throws Exception {
        String firstToken = "a".repeat(300), secondToken = "b".repeat(300);
        try (var pair = new ServerProcessPair(new Auth.Bungee(Set.of(firstToken)), new Auth.Bungee(Set.of(secondToken)))) {
            start(pair.first());
            start(pair.second());
            UUID forwardedId = UUID.randomUUID();
            try (var first = new ProtocolClient(pair.first()); var wrong = new ProtocolClient(pair.second())) {
                first.handshake(forwardedAddress(forwardedId, firstToken), ClientHandshakePacket.Intent.LOGIN);
                first.send(new ClientLoginStartPacket("Forwarded", UUID.randomUUID()));
                wrong.handshake(forwardedAddress(forwardedId, firstToken), ClientHandshakePacket.Intent.LOGIN);
                assertEquals(forwardedId, assertInstanceOf(LoginSuccessPacket.class, first.read()).gameProfile().uuid());
                assertInstanceOf(LoginDisconnectPacket.class, wrong.read());
            }
            try (var second = new ProtocolClient(pair.second())) {
                second.handshake(forwardedAddress(forwardedId, secondToken), ClientHandshakePacket.Intent.LOGIN);
                second.send(new ClientLoginStartPacket("Forwarded", UUID.randomUUID()));
                var profile = assertInstanceOf(LoginSuccessPacket.class, second.read()).gameProfile();
                assertEquals(forwardedId, profile.uuid());
                assertEquals("Forwarded", profile.name());
            }
        }
    }

    private static void start(ServerProcess process) {
        process.setCompressionThreshold(0);
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    private static byte[] signedProfile(Auth.Velocity auth, GameProfile profile) throws Exception {
        byte[] payload = NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, 1);
            buffer.write(NetworkBuffer.STRING, "127.0.0.1");
            buffer.write(GameProfile.SERIALIZER, profile);
        });
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(auth.key());
        byte[] signature = mac.doFinal(payload);
        return NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.RAW_BYTES, signature);
            buffer.write(NetworkBuffer.RAW_BYTES, payload);
        });
    }

    private static String forwardedAddress(UUID uuid, String token) {
        return "localhost\0" + "127.0.0.1\0" + uuid + "\0[{\"name\":\"bungeeguard-token\",\"value\":\"" + token + "\"}]";
    }
}
