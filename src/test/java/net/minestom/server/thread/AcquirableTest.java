package net.minestom.server.thread;

import net.minestom.server.ServerProcess;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AcquirableTest {
    private final ServerProcess process = ServerProcess.create();

    @AfterAll
    void closeProcess() {
        process.close();
    }

    @Test
    public void assignation() throws InterruptedException {
        AtomicReference<TickThread> tickThread = new AtomicReference<>();
        Entity entity = new Entity(process, EntityType.ZOMBIE) {
            @Override
            public void tick(long time) {
                super.tick(time);
                tickThread.set(acquirable().assignedThread());
            }
        };
        Object first = new Object();
        Object second = new Object();

        ThreadDispatcher<Object, Entity> dispatcher = ThreadDispatcher.dispatcher(process, ThreadProvider.counter(), 2);
        dispatcher.start();
        try {
            dispatcher.createPartition(first);
            dispatcher.createPartition(second);

            dispatcher.updateElement(entity, first);
            dispatcher.updateAndAwait(System.nanoTime());
            TickThread firstThread = tickThread.get();
            assertNotNull(firstThread);

            tickThread.set(null);
            dispatcher.updateElement(entity, second);
            dispatcher.updateAndAwait(System.nanoTime());
            TickThread secondThread = tickThread.get();
            assertNotNull(secondThread);

            assertNotEquals(firstThread, secondThread);
        } finally {
            dispatcher.shutdown();
            for (var thread : dispatcher.threads()) thread.join();
        }
    }
}
