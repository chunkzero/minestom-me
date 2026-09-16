package net.minestom.server.instance;

import net.minestom.server.ServerProcess;
import net.minestom.server.tag.Tag;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class InstanceContainerTest {
    private final ServerProcess process = ServerProcess.create();

    @AfterAll
    void closeProcess() {
        process.close();
    }

    @Test
    public void copyPreservesTag() {
        var tag = Tag.String("test");
        var instance = new InstanceContainer(process, UUID.randomUUID(), DimensionType.OVERWORLD);
        instance.setTag(tag, "123");

        var copyInstance = instance.copy();
        var result = copyInstance.getTag(tag);
        assertEquals("123", result);
    }
}
