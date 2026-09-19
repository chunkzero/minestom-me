package net.minestom.testing;

import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventFilter;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.player.GameProfile;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public interface Env extends AutoCloseable {
    /** Creates a test environment. Closing it checks pending expectations and stops the supplied process. */
    static Env create(ServerProcess process) {
        return new EnvImpl(process);
    }

    @Override
    void close();

    ServerProcess process();

    TestConnection createConnection(GameProfile gameProfile);

    default TestConnection createConnection() {
        return createConnection(new GameProfile(UUID.randomUUID(), "RandName"));
    }

    <E extends Event, H> Collector<E> trackEvent(Class<E> eventType, EventFilter<? super E, H> filter, H actor);

    <E extends Event> FlexibleListener<E> listen(Class<E> eventType);

    default void tick() {
        process().ticker().tick(System.nanoTime());
    }

    default boolean tickWhile(BooleanSupplier condition, @Nullable Duration timeout) {
        var ticker = process().ticker();
        final long start = System.nanoTime();
        while (condition.getAsBoolean()) {
            final long tick = System.nanoTime();
            ticker.tick(tick);
            if (timeout != null && System.nanoTime() - start > timeout.toNanos()) {
                return false;
            }
        }
        return true;
    }

    default Player createPlayer(Instance instance, Pos pos) {
        return createConnection().connect(instance, pos);
    }

    default Instance createFlatInstance() {
        return createFlatInstance(null);
    }

    default Instance createFlatInstance(@Nullable ChunkLoader chunkLoader) {
        var instance = process().instanceManager().createInstanceContainer(chunkLoader);
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        return instance;
    }

    default Instance createEmptyInstance() {
        return process().instanceManager().createInstanceContainer();
    }

    default Instance createEmptyInstance(ChunkLoader chunkLoader) {
        return process().instanceManager().createInstanceContainer(chunkLoader);
    }

    default void destroyInstance(Instance instance) {
        process().instanceManager().unregisterInstance(instance);
    }
}
