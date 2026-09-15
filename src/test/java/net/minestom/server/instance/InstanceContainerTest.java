package net.minestom.server.instance;

import net.minestom.server.ServerProcess;
import net.minestom.server.tag.Tag;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InstanceContainerTest {
    private final ServerProcess process = ServerProcess.create();

    @AfterEach
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
