package net.minestom.server.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.ServerProcess;
import net.minestom.server.tag.Tag;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    public void derivedInstancesPreserveProcessAndDimensionName() {
        final Key dimensionName = Key.key("minestom:derived");
        final InstanceContainer instance = new InstanceContainer(
                process, UUID.randomUUID(),
                DimensionType.OVERWORLD, null, dimensionName);

        final InstanceContainer copy = instance.copy();
        final SharedInstance shared = new SharedInstance(UUID.randomUUID(), instance);

        assertSame(process, copy.process());
        assertSame(process, shared.process());
        assertEquals(dimensionName.asString(), copy.getDimensionName());
        assertEquals(dimensionName.asString(), shared.getDimensionName());
    }
}
