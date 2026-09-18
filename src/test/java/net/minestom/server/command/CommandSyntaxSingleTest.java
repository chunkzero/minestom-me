package net.minestom.server.command;

import net.minestom.server.ServerProcess;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static net.minestom.server.command.builder.arguments.ArgumentType.BlockState;
import static net.minestom.server.command.builder.arguments.ArgumentType.EntityType;
import static net.minestom.server.command.builder.arguments.ArgumentType.Group;
import static net.minestom.server.command.builder.arguments.ArgumentType.Integer;
import static net.minestom.server.command.builder.arguments.ArgumentType.Loop;
import static net.minestom.server.command.builder.arguments.ArgumentType.String;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class CommandSyntaxSingleTest {
    @AutoClose
    private static final ServerProcess process = ServerProcess.create();

    @Test
    public void singleInteger() {
        List<Argument<?>> args = List.of(Integer("number"));
        assertSyntax(args, "5", ExpectedExecution.SYNTAX, Map.of("number", 5));
        assertSyntax(args, "5 5", ExpectedExecution.SYNTAX);
        assertSyntax(args, "", ExpectedExecution.DEFAULT);
    }

    @Test
    public void singleIntegerInteger() {
        List<Argument<?>> args = List.of(Integer("number"), Integer("number2"));
        assertSyntax(args, "5", ExpectedExecution.DEFAULT);
        assertSyntax(args, "5 6", ExpectedExecution.SYNTAX, Map.of("number", 5, "number2", 6));
        assertSyntax(args, "", ExpectedExecution.DEFAULT);
    }

    @Test
    public void singleString() {
        List<Argument<?>> args = List.of(String("string"));
        assertSyntax(args, """
                "value"
                """, ExpectedExecution.SYNTAX, Map.of("string", "value"));
        assertSyntax(args, "5 5", ExpectedExecution.SYNTAX);
        assertSyntax(args, "", ExpectedExecution.DEFAULT);
    }

    @Test
    public void singleStringString() {
        List<Argument<?>> args = List.of(String("string"), String("string2"));
        assertSyntax(args, "test", ExpectedExecution.DEFAULT);
        assertSyntax(args, """
                "first" "second"
                """, ExpectedExecution.SYNTAX, Map.of("string", "first", "string2", "second"));
        assertSyntax(args, """
                "unescaped" "esc\\"aped"
                """, ExpectedExecution.SYNTAX, Map.of("string", "unescaped", "string2", "esc\"aped"));
        assertSyntax(args, "5 5", ExpectedExecution.SYNTAX);
        assertSyntax(args, "", ExpectedExecution.DEFAULT);
    }

    @Test
    public void singleGroup() {
        List<Argument<?>> args = List.of(Group("loop", Integer("first"), Integer("second")));
        // 1 2
        {
            var context = new CommandContext(process.command(), "1 2", CommandContext.Purpose.PARSING);
            context.setArg("first", 1, "1");
            context.setArg("second", 2, "2");
            assertSyntax(args, "1 2", ExpectedExecution.SYNTAX, Map.of("loop", context));
        }
        // Incomplete group
        assertSyntax(args, "1", ExpectedExecution.DEFAULT);
    }

    @Test
    public void singleLoop() {
        List<Argument<?>> stringLoop = List.of(Loop("loop", String("value")));
        assertSyntax(stringLoop, "one two three", ExpectedExecution.SYNTAX, Map.of("loop", List.of("one", "two", "three")));

        List<Argument<?>> intLoop = List.of(Loop("loop", Integer("value")));
        assertSyntax(intLoop, "1 2 3", ExpectedExecution.SYNTAX, Map.of("loop", List.of(1, 2, 3)));
    }

    @Test
    public void singleLoopGroup() {
        List<Argument<?>> groupLoop = List.of(Loop("loop", Group("group", Integer("first"), Integer("second"))));
        // 1 2
        {
            var context = new CommandContext(process.command(), "1 2", CommandContext.Purpose.PARSING);
            context.setArg("first", 1, "1");
            context.setArg("second", 2, "2");
            assertSyntax(groupLoop, "1 2", ExpectedExecution.SYNTAX, Map.of("loop", List.of(context)));
        }
        // 1 2 3 4
        {
            var context1 = new CommandContext(process.command(), "1 2", CommandContext.Purpose.PARSING);
            var context2 = new CommandContext(process.command(), "3 4", CommandContext.Purpose.PARSING);

            context1.setArg("first", 1, "1");
            context1.setArg("second", 2, "2");

            context2.setArg("first", 3, "3");
            context2.setArg("second", 4, "4");

            assertSyntax(groupLoop, "1 2 3 4", ExpectedExecution.SYNTAX, Map.of("loop", List.of(context1, context2)));
        }
        // Incomplete loop
        assertSyntax(groupLoop, "1", ExpectedExecution.DEFAULT);
        assertSyntax(groupLoop, "1 2 3", ExpectedExecution.DEFAULT);
        assertSyntax(groupLoop, "1 2 3 4 5", ExpectedExecution.DEFAULT);
    }

    @Test
    public void singleLoopDoubleGroup() {
        List<Argument<?>> groupLoop = List.of(
                Loop("loop",
                        Group("group", BlockState("block"), EntityType("entity_type")),
                        Group("group2", EntityType("entity_type"), BlockState("block"))
                )
        );
        // block enchant
        {
            var input = "minecraft:stone minecraft:allay";
            var context = new CommandContext(process.command(), input, CommandContext.Purpose.PARSING);
            context.setArg("block", Block.STONE, "minecraft:stone");
            context.setArg("entity_type", EntityType.ALLAY, "minecraft:allay");
            assertSyntax(groupLoop, input, ExpectedExecution.SYNTAX, Map.of("loop", List.of(context)));
        }
        // enchant block block enchant
        {
            var context1 = new CommandContext(process.command(), "minecraft:allay minecraft:stone", CommandContext.Purpose.PARSING);
            var context2 = new CommandContext(process.command(), "minecraft:grass_block minecraft:zombie", CommandContext.Purpose.PARSING);

            context1.setArg("entity_type", EntityType.ALLAY, "minecraft:allay");
            context1.setArg("block", Block.STONE, "minecraft:stone");

            context2.setArg("block", Block.GRASS_BLOCK, "minecraft:grass_block");
            context2.setArg("entity_type", EntityType.ZOMBIE, "minecraft:zombie");

            var input = context1.getInput() + " " + context2.getInput();
            assertSyntax(groupLoop, input, ExpectedExecution.SYNTAX, Map.of("loop", List.of(context1, context2)));
        }
        // Incomplete loop
        assertSyntax(groupLoop, "minecraft:allay", ExpectedExecution.DEFAULT);
        assertSyntax(groupLoop, "minecraft:allay minecraft:allay", ExpectedExecution.DEFAULT);
        assertSyntax(groupLoop, "minecraft:stone", ExpectedExecution.DEFAULT);
        assertSyntax(groupLoop, "minecraft:stone minecraft:stone", ExpectedExecution.DEFAULT);
    }

    private static void assertSyntax(List<Argument<?>> args, String input, ExpectedExecution expectedExecution, @Nullable Map<String, Object> expectedValues) {
        final String commandName = "name";

        var manager = process.command();
        if (manager.getCommand(commandName) != null) manager.unregister(manager.getCommand(commandName));
        var command = new Command(commandName);
        manager.register(command);

        AtomicReference<ExpectedExecution> result = new AtomicReference<>();
        AtomicReference<Map<String, Object>> values = new AtomicReference<>();

        command.setDefaultExecutor((_, _) -> {
            if (!result.compareAndSet(null, ExpectedExecution.DEFAULT)) {
                fail("Multiple execution: " + result.get());
            }
        });

        command.addSyntax((_, context) -> {
            if (!result.compareAndSet(null, ExpectedExecution.SYNTAX)) {
                fail("Multiple execution: " + result.get());
            }
            values.set(context.getMap());
        }, args.toArray(Argument[]::new));

        final String executeString = commandName + " " + input;
        manager.executeServerCommand(executeString);
        assertEquals(expectedExecution, result.get());
        if (expectedValues != null) {
            assertEquals(expectedValues, values.get());
        }
    }

    private static void assertSyntax(List<Argument<?>> args, String input, ExpectedExecution expectedExecution) {
        assertSyntax(args, input, expectedExecution, null);
    }

    enum ExpectedExecution {
        DEFAULT,
        SYNTAX
    }
}
