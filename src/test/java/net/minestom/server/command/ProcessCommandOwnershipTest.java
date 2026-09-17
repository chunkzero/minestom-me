package net.minestom.server.command;

import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.minestom.server.ServerProcess;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.exception.ArgumentSyntaxException;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.instrument.Instrument;
import net.minestom.server.listener.TabCompleteListener;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.tag.TagHandler;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandOwnershipTest {
    @Test
    void parsingDefaultsConditionsSuggestionsAndErrorsReceiveTheExecutingContext() {
        try (var pair = new ServerProcessPair()) {
            pair.first().setBrandName("first");
            pair.second().setBrandName("second");
            var instrument = Instrument.create(SoundEvent.ITEM_GOAT_HORN_SOUND_0, 1, 16, Component.text("test"));
            pair.first().registries().instrument().register("test:first", instrument);
            pair.second().registries().instrument().register("test:second", instrument);
            var phases = new ArrayList<String>();
            var expected = new AtomicReference<ServerProcess>();
            var count = ArgumentType.Integer("count").setDefaultValue((_, context) -> {
                assertSame(expected.get(), context.process());
                phases.add("default-count");
                return 7;
            });
            var value = new Argument<String>("value") {
                @Override
                public String parse(CommandSender sender, CommandContext context, String input) {
                    assertSame(expected.get(), context.process());
                    assertSame(expected.get().command(), context.commandManager());
                    assertEquals(7, context.get(count));
                    phases.add("parse");
                    if (input.equals("bad")) throw new ArgumentSyntaxException("bad value", input, 42);
                    return context.process().brandName();
                }

                @Override
                public ArgumentParserType parser() {
                    return ArgumentParserType.STRING;
                }
            };
            value.setDefaultValue((_, context) -> {
                assertSame(expected.get(), context.process());
                assertEquals(7, context.get(count));
                phases.add("default-value");
                return context.process().brandName();
            });
            value.setSuggestionCallback((_, context, suggestion) -> {
                assertSame(expected.get(), context.process());
                assertEquals(7, context.get(count));
                var key = context.process().registries().instrument().getKey(Key.key("test:" + context.process().brandName()));
                assertNotNull(key);
                suggestion.addEntry(new SuggestionEntry(key.name()));
            });
            value.setCallback((_, context, error) -> {
                assertSame(expected.get(), context.process());
                assertEquals(7, context.get(count));
                assertEquals(42, error.getErrorCode());
                assertEquals("bad", error.getInput());
                phases.add("error");
            });
            var command = new Command("same") {
                @Override
                public void globalListener(CommandSender sender, CommandContext context, String input) {
                    assertSame(expected.get(), context.process());
                    phases.add("global");
                }
            };
            command.setCondition((_, context) -> {
                assertSame(expected.get(), context.process());
                phases.add("condition");
                return true;
            });
            command.addSyntax((_, context) -> {
                assertSame(expected.get(), context.process());
                assertEquals(context.process().brandName(), context.get(value));
                phases.add("execute");
            }, count, value);
            pair.first().command().register(command);
            pair.second().command().register(command);
            for (var process : List.of(pair.first(), pair.second())) {
                expected.set(process);
                for (var sender : List.of(process.command().getConsoleSender(), new CustomSender())) {
                    phases.clear();
                    assertEquals(CommandResult.Type.SUCCESS, process.command().execute(sender, "same 7 valid").getType());
                    assertEquals(List.of("parse", "global", "condition", "execute"), phases);
                    phases.clear();
                    assertEquals(CommandResult.Type.SUCCESS, process.command().execute(sender, "same").getType());
                    assertEquals(List.of("default-count", "default-value", "global", "condition", "execute"), phases);
                    var suggestion = TabCompleteListener.getSuggestion(process.command(), sender, "same 7 v");
                    assertNotNull(suggestion);
                    assertEquals(List.of("test:" + process.brandName()), suggestion.getEntries().stream().map(SuggestionEntry::getEntry).toList());
                    phases.clear();
                    assertEquals(CommandResult.Type.INVALID_SYNTAX, process.command().execute(sender, "same 7 bad").getType());
                    assertEquals(List.of("parse", "global", "condition", "error"), phases);
                }
            }
        }
    }

    @Test
    void registryArgumentsKeepContextThroughMappingGroupsLoopsAndNestedCommands() {
        try (var pair = new ServerProcessPair()) {
            var instrument = Instrument.create(SoundEvent.ITEM_GOAT_HORN_SOUND_0, 1, 16, Component.text("test"));
            var firstKey = pair.first().registries().instrument().register("test:first", instrument);
            var secondKey = pair.second().registries().instrument().register("test:second", instrument);
            var item = ArgumentType.ItemStack("item").map(stack -> stack).filter(stack -> stack.has(DataComponents.INSTRUMENT));
            var marker = ArgumentType.Literal("item");
            var contextualItem = new Argument<ItemStack>("checked-item", true) {
                @Override
                public ItemStack parse(CommandSender sender, CommandContext context, String input) {
                    assertEquals("item", context.get(marker));
                    return item.parse(sender, context, input);
                }

                @Override
                public ArgumentParserType parser() {
                    return item.parser();
                }
            };
            var group = ArgumentType.Group("group", marker, contextualItem);
            var loop = ArgumentType.Loop("items", group);
            var result = new AtomicReference<ItemStack>();
            var owner = new AtomicReference<ServerProcess>();
            var inner = new Command("inner");
            inner.addSyntax((_, context) -> {
                owner.set(context.process());
                var nested = context.get(loop).getFirst();
                assertSame(context.commandManager(), nested.commandManager());
                result.set(nested.get(contextualItem));
            }, loop);
            var nested = ArgumentType.Command("nested").setOnlyCorrect(true);
            var outer = new Command("outer");
            outer.addSyntax((sender, context) -> context.get(nested).getParsedCommand().execute(sender), nested);
            for (var process : List.of(pair.first(), pair.second())) process.command().register(inner, outer);
            for (var process : List.of(pair.first(), pair.second())) {
                var key = process == pair.first() ? firstKey : secondKey;
                String input = "outer inner item minecraft:goat_horn[minecraft:instrument=\"" + key.name() + "\"]";
                assertEquals(CommandResult.Type.SUCCESS, process.command().executeServerCommand(input).getType());
                assertSame(process, owner.get());
                assertEquals(key, result.get().get(DataComponents.INSTRUMENT));
            }
            result.set(null);
            assertEquals(CommandResult.Type.INVALID_SYNTAX, pair.second().command().executeServerCommand(
                    "outer inner item minecraft:goat_horn[minecraft:instrument=\"test:first\"]").getType());
            assertNull(result.get());
            assertThrows(IllegalStateException.class, () -> ArgumentType.ItemStack("item").parse(new CustomSender(), "stone"));
        }
    }

    @Test
    void argumentErrorCallbacksRespectTheSyntaxConditionAndItsContext() {
        try (var pair = new ServerProcessPair()) {
            var callbacks = new ArrayList<ServerProcess>();
            var argument = ArgumentType.Word("restricted").from("yes");
            argument.setCallback((_, context, _) -> callbacks.add(context.process()));
            var command = new Command("restricted");
            command.addConditionalSyntax((_, context) -> context.process() == pair.second(), (_, _) -> {}, argument);
            pair.first().command().register(command);
            pair.second().command().register(command);
            assertEquals(CommandResult.Type.CANCELLED, pair.first().command().executeServerCommand("restricted no").getType());
            assertTrue(callbacks.isEmpty());
            assertEquals(CommandResult.Type.INVALID_SYNTAX, pair.second().command().executeServerCommand("restricted no").getType());
            assertEquals(List.of(pair.second()), callbacks);
        }
    }

    @Test
    void cachedExecutablesUnknownCallbacksAndFailuresStayWithTheirManager() {
        try (var pair = new ServerProcessPair()) {
            var firstErrors = new ArrayList<Throwable>();
            var secondErrors = new ArrayList<Throwable>();
            pair.first().exception().setExceptionHandler(firstErrors::add);
            pair.second().exception().setExceptionHandler(secondErrors::add);
            var expected = new IllegalStateException("second command");
            var command = new Command("fail");
            command.setDefaultExecutor((_, context) -> {
                assertSame(pair.second(), context.process());
                throw expected;
            });
            var manager = new CommandManager(pair.second());
            manager.register(command);
            var callback = new AtomicReference<CommandContext>();
            manager.setUnknownCommandCallback((_, context) -> callback.set(context));
            assertEquals(CommandResult.Type.UNKNOWN, manager.executeServerCommand("missing").getType());
            assertSame(manager, callback.get().commandManager());
            assertEquals("missing", callback.get().getInput());
            var executable = manager.parseCommand(new CustomSender(), "fail").executable();
            assertEquals(ExecutableCommand.Result.Type.EXECUTOR_EXCEPTION, executable.execute(new CustomSender()).type());
            assertTrue(firstErrors.isEmpty());
            assertEquals(List.of(expected), secondErrors);
        }
    }

    private static final class CustomSender implements CommandSender {
        private final TagHandler tags = TagHandler.newHandler();

        @Override
        public TagHandler tagHandler() {
            return tags;
        }

        @Override
        public Identity identity() {
            return Identity.nil();
        }
    }
}
