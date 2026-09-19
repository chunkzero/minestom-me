package net.minestom.server.utils.entity;

import net.minestom.server.command.CommandSender;
import net.minestom.server.command.ExecutableCommand;
import net.minestom.server.command.ServerSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.player.GameProfile;
import net.minestom.testing.Env;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessEntityFinderTest {
    @Test
    void sharedCommandCreatesOwnedFindersThatResolveLiveState() {
        try (var pair = new ServerProcessPair(); var a = Env.create(pair.first()); var b = Env.create(pair.second())) {
            var profile = new GameProfile(UUID.randomUUID(), "SameName");
            var first = a.createConnection(profile).connect(a.createEmptyInstance(), Pos.ZERO);
            var second = b.createConnection(profile).connect(b.createEmptyInstance(), Pos.ZERO);
            var argument = ArgumentType.Entity("target");
            var result = new AtomicReference<EntityFinder>();
            var command = new Command("select");
            command.addSyntax((_, context) -> {
                var finder = context.get(argument);
                assertSame(context.process(), finder.process());
                result.set(finder);
            }, argument);
            pair.first().commandManager().register(command);
            pair.second().commandManager().register(command);

            for (var player : List.of(first, second)) {
                var manager = player.process().commandManager();
                for (CommandSender sender : List.of(manager.getConsoleSender(), new ServerSender())) {
                    for (var input : List.of("@a", "@e[type=player]", "SameName")) {
                        assertEquals(CommandResult.Type.SUCCESS, manager.execute(sender, "select " + input).getType());
                        assertEquals(List.of(player), result.get().find(sender));
                        assertSame(player, result.get().findFirstPlayer(sender));
                        assertSame(player, result.get().findFirstEntity(sender));
                    }
                }
                assertEquals(CommandResult.Type.SUCCESS, manager.execute(player, "select " + profile.uuid()).getType());
                assertSame(player, result.get().findFirstEntity(player));
            }
            assertThrows(IllegalStateException.class, () -> argument.parse(new ServerSender(), "@a"));

            var sender = new ServerSender();
            var cached = pair.first().commandManager().parseCommand(sender, "select @a").executable();
            assertEquals(ExecutableCommand.Result.Type.SUCCESS, cached.execute(sender).type());
            var firstFinder = result.get();
            assertEquals(CommandResult.Type.SUCCESS, pair.second().commandManager().execute(sender, "select @a").getType());
            var secondFinder = result.get();
            assertNotSame(firstFinder, secondFinder);
            assertEquals(CommandResult.Type.SUCCESS, pair.first().commandManager().execute(sender, "select @a").getType());
            assertNotSame(firstFinder, result.get());

            var later = a.createConnection().connect(first.getInstance(), Pos.ZERO);
            assertEquals(Set.of(first, later), Set.copyOf(firstFinder.find(sender)));
            assertEquals(ExecutableCommand.Result.Type.SUCCESS, cached.execute(sender).type());
            assertSame(firstFinder, result.get());
            assertSame(pair.first(), result.get().process());
            assertThrows(IllegalArgumentException.class, () -> firstFinder.find(second));
            assertThrows(IllegalArgumentException.class, () -> firstFinder.find(second.getInstance(), null));
            assertThrows(IllegalArgumentException.class, () -> firstFinder.find(first.getInstance(), second));

            firstFinder.setTargetSelector(EntityFinder.TargetSelector.SELF);
            assertTrue(firstFinder.find(sender).isEmpty());
            assertSame(first, firstFinder.findFirstEntity(first));
            assertEquals(List.of(second), secondFinder.find(sender));
            pair.first().close();
            assertEquals(List.of(second), secondFinder.find(sender));
        }
    }

    @Test
    void nestedMappedGroupedAndLoopedSelectorsRetainTheParsingOwner() {
        try (var pair = new ServerProcessPair(); var a = Env.create(pair.first()); var b = Env.create(pair.second())) {
            var profile = new GameProfile(UUID.randomUUID(), "SameName");
            var first = a.createConnection(profile).connect(a.createEmptyInstance(), Pos.ZERO);
            var second = b.createConnection(profile).connect(b.createEmptyInstance(), Pos.ZERO);
            var target = ArgumentType.Entity("target").map(finder -> finder.setLimit(1));
            var group = ArgumentType.Group("group", ArgumentType.Literal("for"), target);
            var groups = ArgumentType.Loop("groups", group);
            var inner = new Command("inner");
            var results = new AtomicReference<List<EntityFinder>>();
            inner.addSyntax((sender, context) -> {
                var finders = context.get(groups).stream().map(nested -> nested.get(target)).toList();
                for (var finder : finders) {
                    assertSame(context.process(), finder.process());
                    assertEquals(List.of(context.process() == pair.first() ? first : second), finder.find(sender));
                }
                results.set(finders);
            }, groups);
            var nested = ArgumentType.Command("nested").setOnlyCorrect(true);
            var outer = new Command("outer");
            outer.addSyntax((sender, context) -> context.get(nested).getParsedCommand().execute(sender), nested);
            pair.first().commandManager().register(inner, outer);
            pair.second().commandManager().register(inner, outer);
            for (var process : List.of(pair.first(), pair.second())) {
                assertEquals(CommandResult.Type.SUCCESS,
                        process.commandManager().executeServerCommand("outer inner for @a for SameName").getType());
                assertEquals(2, results.get().size());
                assertNotSame(results.get().getFirst(), results.get().getLast());
            }
        }
    }
}
