package net.minestom.server.entity.player;

import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.ServerDifficultyPacket;
import net.minestom.server.world.Difficulty;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@EnvTest
class PlayerDifficultyIntegrationTest {
    @Test
    void joinAndRespawnUseTheConnectionsProcess(Env defaultEnv) {
        defaultEnv.process().setDifficulty(Difficulty.HARD);
        try (var process = ServerProcess.create(); var env = Env.create(process)) {
            process.setDifficulty(Difficulty.PEACEFUL);
            var connection = env.createConnection();
            var joining = connection.trackIncoming(ServerDifficultyPacket.class);
            var player = connection.connect(env.createEmptyInstance(), Pos.ZERO);
            assertSame(process, player.getPlayerConnection().process());
            assertEquals(List.of(new ServerDifficultyPacket(Difficulty.PEACEFUL, true)), joining.collect());

            player.kill();
            var respawning = connection.trackIncoming(ServerDifficultyPacket.class);
            player.respawn();
            assertEquals(List.of(new ServerDifficultyPacket(Difficulty.PEACEFUL, false)), respawning.collect());
            assertEquals(Difficulty.HARD, defaultEnv.process().difficulty());
        }
    }
}
