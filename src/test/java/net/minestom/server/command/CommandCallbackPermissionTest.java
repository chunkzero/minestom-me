package net.minestom.server.command;

import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.exception.ArgumentSyntaxException;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.listener.TabCompleteListener;
import net.minestom.testing.ServerProcessPair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCallbackPermissionTest {
    @Test
    void conditionsDistinguishSuggestionsFromExecution() {
        try (var pair = new ServerProcessPair()) {
            var purposes = new ArrayList<CommandContext.Purpose>();
            var number = ArgumentType.Integer("number");
            number.setSuggestionCallback((_, context, suggestion) -> {
                assertEquals(CommandContext.Purpose.SUGGESTION, context.purpose());
                suggestion.addEntry(new SuggestionEntry("42"));
            });
            number.setCallback((_, context, _) -> assertEquals(CommandContext.Purpose.EXECUTION, context.purpose()));
            var command = new Command("purpose");
            command.setCondition((_, context) -> {
                purposes.add(context.purpose());
                return true;
            });
            command.addSyntax((_, context) -> assertEquals(CommandContext.Purpose.EXECUTION, context.purpose()), number);
            var manager = pair.first().commandManager();
            manager.register(command);
            assertNotNull(TabCompleteListener.getSuggestion(manager, manager.getConsoleSender(), "purpose invalid"));
            assertEquals(CommandResult.Type.INVALID_SYNTAX, manager.executeServerCommand("purpose invalid").getType());
            assertEquals(CommandResult.Type.SUCCESS, manager.executeServerCommand("purpose 42").getType());
            assertEquals(List.of(CommandContext.Purpose.SUGGESTION, CommandContext.Purpose.EXECUTION, CommandContext.Purpose.EXECUTION), purposes);
        }
    }

    @Test
    void failedCustomArgumentCannotMutateASiblingContext() {
        try (var pair = new ServerProcessPair()) {
            var mutating = new Argument<String>("mutating") {
                @Override
                public String parse(CommandSender sender, CommandContext context, String input) {
                    assertEquals(CommandContext.Purpose.PARSING, context.purpose());
                    context.getMap().put("leaked", true);
                    context.setArg("raw-leak", "value", "raw");
                    throw new ArgumentSyntaxException("try sibling", input, 1);
                }

                @Override
                public ArgumentParserType parser() {
                    return ArgumentParserType.STRING;
                }
            };
            var sibling = new Argument<String>("sibling") {
                @Override
                public String parse(CommandSender sender, CommandContext context, String input) {
                    assertFalse(context.has("leaked"));
                    assertFalse(context.has("raw-leak"));
                    assertNull(context.getRaw("raw-leak"));
                    return input;
                }

                @Override
                public ArgumentParserType parser() {
                    return ArgumentParserType.STRING;
                }
            };
            var executed = new AtomicInteger();
            var command = new Command("isolated");
            command.addSyntax((_, _) -> {}, mutating);
            command.addSyntax((_, context) -> {
                assertEquals("value", context.get(sibling));
                assertFalse(context.has("leaked"));
                executed.incrementAndGet();
            }, sibling);
            pair.first().commandManager().register(command);
            assertEquals(CommandResult.Type.SUCCESS, pair.first().commandManager().executeServerCommand("isolated value").getType());
            assertEquals(1, executed.get());
        }
    }

    @Test
    void earlyArgumentErrorsRequireAnApplicableSyntaxPermission() {
        try (var pair = new ServerProcessPair()) {
            var calls = new ArrayList<CommandContext>();
            var first = ArgumentType.Integer("first");
            first.setCallback((_, context, error) -> {
                assertEquals("invalid", error.getInput());
                calls.add(context);
            });
            var command = new Command("restricted");
            command.addConditionalSyntax((_, context) -> context.process() == pair.second(), (_, _) -> {},
                    first, ArgumentType.Word("last"));
            pair.first().commandManager().register(command);
            pair.second().commandManager().register(command);

            assertEquals(CommandResult.Type.CANCELLED, pair.first().commandManager()
                    .executeServerCommand("restricted invalid last").getType());
            assertTrue(calls.isEmpty());
            assertEquals(CommandResult.Type.INVALID_SYNTAX, pair.second().commandManager()
                    .executeServerCommand("restricted invalid last").getType());
            assertEquals(1, calls.size());
            assertSame(pair.second(), calls.getFirst().process());
        }
    }

    @Test
    void suggestionsRequirePermissionWithoutAnErrorCallback() {
        try (var pair = new ServerProcessPair()) {
            for (boolean multipleArguments : List.of(false, true)) {
                var calls = new AtomicInteger();
                var first = ArgumentType.Integer("first");
                first.setSuggestionCallback((_, _, suggestion) -> {
                    calls.incrementAndGet();
                    suggestion.addEntry(new SuggestionEntry("42"));
                });
                var command = new Command("restricted" + multipleArguments);
                command.addConditionalSyntax((_, context) -> context.process() == pair.second(), (_, _) -> {},
                        multipleArguments ? new Argument<?>[]{first, ArgumentType.Word("last")}
                                : new Argument<?>[]{first});
                pair.first().commandManager().register(command);
                pair.second().commandManager().register(command);

                assertNull(TabCompleteListener.getSuggestion(pair.first().commandManager(), pair.first().commandManager().getConsoleSender(),
                        command.getName() + " invalid"));
                assertEquals(0, calls.get());
                var suggestion = TabCompleteListener.getSuggestion(pair.second().commandManager(), pair.second().commandManager().getConsoleSender(),
                        command.getName() + " invalid");
                assertNotNull(suggestion);
                assertEquals(List.of("42"), suggestion.getEntries().stream().map(SuggestionEntry::getEntry).toList());
                assertEquals(1, calls.get());
            }
        }
    }

    @Test
    void sharedArgumentCallbacksPermitAnyApplicableSyntax() {
        try (var pair = new ServerProcessPair()) {
            var calls = new AtomicInteger();
            var first = ArgumentType.Integer("first");
            first.setCallback((_, _, _) -> calls.incrementAndGet());
            first.setSuggestionCallback((_, _, suggestion) -> suggestion.addEntry(new SuggestionEntry("42")));
            var command = new Command("shared");
            command.addConditionalSyntax((_, _) -> false, (_, _) -> {}, first, ArgumentType.Literal("denied"));
            command.addConditionalSyntax((_, context) -> context.process() == pair.second(), (_, _) -> {},
                    first, ArgumentType.Literal("allowed"));
            pair.first().commandManager().register(command);
            pair.second().commandManager().register(command);
            assertEquals(CommandResult.Type.CANCELLED, pair.first().commandManager().executeServerCommand("shared invalid").getType());
            assertNull(TabCompleteListener.getSuggestion(pair.first().commandManager(), pair.first().commandManager().getConsoleSender(), "shared invalid"));
            assertEquals(0, calls.get());
            assertEquals(CommandResult.Type.INVALID_SYNTAX, pair.second().commandManager().executeServerCommand("shared invalid").getType());
            assertNotNull(TabCompleteListener.getSuggestion(pair.second().commandManager(), pair.second().commandManager().getConsoleSender(), "shared invalid"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void shorterSyntaxConditionDoesNotGateALongerSyntaxCallback() {
        try (var pair = new ServerProcessPair()) {
            var calls = new AtomicInteger();
            var first = ArgumentType.Integer("first");
            var last = ArgumentType.Integer("last");
            last.setCallback((_, _, _) -> calls.incrementAndGet());
            last.setSuggestionCallback((_, _, suggestion) -> suggestion.addEntry(new SuggestionEntry("42")));
            var command = new Command("shared");
            command.addConditionalSyntax((_, _) -> false, (_, _) -> {}, first);
            command.addConditionalSyntax((_, context) -> context.process() == pair.second(), (_, _) -> {}, first, last);
            pair.first().commandManager().register(command);
            pair.second().commandManager().register(command);
            assertEquals(CommandResult.Type.CANCELLED, pair.first().commandManager().executeServerCommand("shared 1 invalid").getType());
            assertEquals(0, calls.get());
            assertEquals(CommandResult.Type.INVALID_SYNTAX, pair.second().commandManager().executeServerCommand("shared 1 invalid").getType());
            assertNotNull(TabCompleteListener.getSuggestion(pair.second().commandManager(), pair.second().commandManager().getConsoleSender(), "shared 1 invalid"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void argumentCallbackPrecedesDefaultExecutorAndTrailingDataHasItsOwnError() {
        try (var pair = new ServerProcessPair()) {
            var defaults = new AtomicInteger();
            var errors = new AtomicInteger();
            var number = ArgumentType.Integer("number");
            number.setCallback((_, _, _) -> errors.incrementAndGet());
            var command = new Command("number");
            command.setDefaultExecutor((_, _) -> defaults.incrementAndGet());
            command.addSyntax((_, _) -> {}, number);
            var trailing = new Command("trailing");
            var manager = pair.first().commandManager();
            manager.register(command, trailing);
            assertEquals(CommandResult.Type.INVALID_SYNTAX, manager.executeServerCommand("number invalid").getType());
            assertEquals(1, errors.get());
            assertEquals(0, defaults.get());
            assertEquals(CommandResult.Type.SUCCESS, manager.executeServerCommand("number").getType());
            assertEquals(1, defaults.get());
            var result = assertInstanceOf(CommandParserImpl.InvalidCommand.class,
                    manager.parseCommand(manager.getConsoleSender(), "trailing extra"));
            assertEquals("Command has trailing data.", result.error().message());
            assertEquals("trailing extra", result.error().input());
        }
    }
}
