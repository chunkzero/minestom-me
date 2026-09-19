package net.minestom.server.command;

import net.minestom.server.command.Graph.Node;
import net.minestom.server.command.builder.ArgumentCallback;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.CommandData;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.condition.CommandCondition;
import net.minestom.server.command.builder.exception.ArgumentSyntaxException;
import net.minestom.server.command.builder.suggestion.Suggestion;
import net.minestom.server.command.builder.suggestion.SuggestionCallback;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

final class CommandParserImpl implements CommandParser {
    static final CommandParserImpl PARSER = new CommandParserImpl();

    static final class Chain {
        final CommandContext context;
        @Nullable ArgumentCallback errorCallback;
        @Nullable ArgumentSyntaxException error;
        @Nullable CommandCondition errorCondition;
        @Nullable CommandCondition suggestionCondition;

        @Nullable CommandExecutor defaultExecutor = null;
        @Nullable SuggestionCallback suggestionCallback = null;
        final ArrayDeque<NodeResult> nodeResults = new ArrayDeque<>();
        final List<CommandCondition> conditions = new ArrayList<>();
        final List<CommandExecutor> globalListeners = new ArrayList<>();

        void append(NodeResult result) {
            this.nodeResults.add(result);
            if (result.argumentResult instanceof ArgumentResult.Success<?> success) {
                context.setArg(result.name(), success.value(), success.input());
            } else if (size() > 1 && result.argumentResult instanceof ArgumentResult.IncompatibleType<?> failure) {
                errorCallback = result.node.argument().getCallback();
                error = failure.exception();
                if (errorCallback != null) errorCondition = callbackCondition(result.node);
            }
            final Graph.Execution execution = result.node.execution();
            if (execution != null) {
                // Create condition chain
                final CommandCondition condition = execution.condition();
                if (condition != null) conditions.add(condition);

                // Track default executor
                final CommandExecutor defExec = execution.defaultExecutor();
                if (defExec != null) defaultExecutor = defExec;

                // Merge global listeners
                final CommandExecutor globalListener = execution.globalListener();
                if (globalListener != null) globalListeners.add(globalListener);
            }
        }

        CommandCondition mergedConditions() {
            return (sender, context) -> {
                for (CommandCondition condition : conditions) {
                    if (!condition.canUse(sender, context)) return false;
                }
                return true;
            };
        }

        CommandCondition callbackCondition(Node node) {
            // A shorter syntax ending at an ancestor does not govern this callback's syntax.
            var commands = nodeResults.stream().map(result -> result.node.execution())
                    .filter(execution -> execution != null && execution.globalListener() != null).toList();
            var syntaxCondition = node.callbackCondition();
            return (sender, context) -> {
                for (var command : commands) {
                    if (!command.test(sender, context)) return false;
                }
                return syntaxCondition == null || syntaxCondition.canUse(sender, context);
            };
        }

        void suggestion(Node node) {
            var callback = node.argument().getSuggestionCallback();
            if (callback == null) return;
            suggestionCallback = callback;
            suggestionCondition = callbackCondition(node);
        }

        CommandExecutor mergedGlobalExecutors() {
            return (sender, context) -> globalListeners.forEach(x -> x.apply(sender, context));
        }

        Map<String, ArgumentResult<Object>> collectArguments() {
            return nodeResults.stream()
                    .skip(2) // skip root node and command
                    .collect(Collectors.toUnmodifiableMap(NodeResult::name, NodeResult::argumentResult));
        }

        List<Argument<?>> getArgs() {
            return nodeResults.stream().map(x -> x.node.argument()).collect(Collectors.toList());
        }

        int size() {
            return nodeResults.size();
        }

        /**
         * Calculates the depth of the chain that is considered successful or valid, providing a more accurate measure
         * for deciding which chain is the most reliable to use. For example a chain that contains the following
         * values [, foo, bar, baz] given the command input "foo bar" will have a successful depth of 2.
         *
         * @return The successful result depth
         * @see #size() getting the size of all results
         */
        int depth() {
            int depth = 0;

            for (NodeResult node : this.nodeResults) {
                if (depth++ == 0) {
                    // If we're on the first node, skip it and increment, we don't care about the empty first node
                    continue;
                }

                // If this node isn't a success, we're going to stop counting the depth and stop here
                if (!(node.argumentResult() instanceof ArgumentResult.Success<?>)) {
                    depth--;
                    break;
                }
            }

            // The chain will always contain a empty node at the start, we don't care about it so we'll remove one
            return depth - 1;
        }

        /**
         * Gets the last successful argument result in the chain (breaking if hitting a non-successful result). This
         * method is very similar in how {@link #depth()}'s functions, and is used to get the last successful result
         *
         * @return The last successful result, or null if there isn't a good result to give back, such as if
         * the depth of the chain is zero (containing only an empty node result, or if no node results exist).
         * @see #depth() the depth size of the chain
         * @see #nodeResults all the node results in the chain
         */
        @Nullable NodeResult lastSuccessfulResult() {
            // Early exit if node results is empty or has only the empty node element
            if (this.nodeResults.size() <= 1) return null;

            NodeResult previousNode = null;
            for (NodeResult node : this.nodeResults) {
                // We want to just skip the initial node, we never want to return it
                if (previousNode == null) {
                    previousNode = node;
                    continue;
                }

                // If this node isn't a success, we're going to stop counting the depth and stop here
                if (!(node.argumentResult() instanceof ArgumentResult.Success<?>)) {
                    return previousNode;
                }

                previousNode = node;
            }

            return previousNode;
        }

        Chain(CommandContext context) {
            this.context = context;
        }

        Chain(CommandContext context, @Nullable CommandExecutor defaultExecutor,
              @Nullable SuggestionCallback suggestionCallback,
              ArrayDeque<NodeResult> nodeResults,
              List<CommandCondition> conditions,
              List<CommandExecutor> globalListeners) {
            this.context = context;
            this.defaultExecutor = defaultExecutor;
            this.suggestionCallback = suggestionCallback;
            this.nodeResults.addAll(nodeResults);
            this.conditions.addAll(conditions);
            this.globalListeners.addAll(globalListeners);
        }

        Chain fork() {
            var copy = new Chain(context.fork(), defaultExecutor, suggestionCallback, nodeResults, conditions, globalListeners);
            copy.errorCallback = errorCallback;
            copy.error = error;
            copy.errorCondition = errorCondition;
            copy.suggestionCondition = suggestionCondition;
            return copy;
        }
    }

    @Override
    public CommandParser.Result parse(CommandManager manager, CommandSender sender, Graph graph, String input) {
        manager.checkSender(sender);
        final CommandStringReader reader = new CommandStringReader(input);
        Node parent = graph.root();

        NodeResult result = parseNode(sender, parent, new Chain(new CommandContext(manager, input, CommandContext.Purpose.PARSING)), reader);
        Chain chain = result.chain();

        NodeResult lastNodeResult = chain.nodeResults.peekLast();
        if (lastNodeResult == null) return new UnknownCommandResult(manager);
        Node lastNode = lastNodeResult.node;
        if (chain.errorCallback != null && chain.error != null) return InvalidCommand.invalid(input, chain);

        if (result.argumentResult instanceof ArgumentResult.Success<?>) {
            CommandExecutor executor = nullSafeGetter(lastNode.execution(), Graph.Execution::executor);
            if (executor != null) return ValidCommand.executor(input, chain, executor);
        }

        // If here, then the command failed or didn't have an executor, then this isn't a known command
        if (chain.depth() < 1) return new UnknownCommandResult(manager);

        // Look for a default executor, or give up if we got nowhere
        if (lastNode.equals(parent)) return new UnknownCommandResult(manager);

        final @Nullable ValidCommand defaultExecutor = ValidCommand.defaultExecutor(input, chain);
        if (defaultExecutor != null) return defaultExecutor;

        return InvalidCommand.invalid(input, chain);
    }

    @Contract("null, _ -> null; !null, null -> fail; !null, !null -> _")
    private static <R, T> @Nullable R nullSafeGetter(@Nullable T obj, Function<T, R> getter) {
        return obj == null ? null : getter.apply(obj);
    }

    private static NodeResult parseNode(CommandSender sender, Node node, Chain chain, CommandStringReader reader) {
        chain = chain.fork();
        Argument<?> argument = node.argument();
        int start = reader.cursor();

        if (reader.hasRemaining()) {
            chain.suggestion(node);
            SuggestionCallback suggestionCallback = argument.getSuggestionCallback();
            ArgumentResult<?> result = parseArgument(sender, chain.context, argument, reader);
            @SuppressWarnings("unchecked")
            NodeResult nodeResult = new NodeResult(node, chain, (ArgumentResult<Object>) result, suggestionCallback);
            chain.append(nodeResult);
            if (chain.size() == 1) { // If this is the root node (usually "Literal<>")
                reader.cursor(start);
            } else {
                if (!(result instanceof ArgumentResult.Success<?>)) {
                    reader.cursor(start);
                    return nodeResult;
                }
            }
        } else {
            // Nothing left, yet we're still being asked to parse? There must be defaults then
            var defaultSupplier = node.argument().getDefaultValue();
            if (defaultSupplier != null) {
                Object value = defaultSupplier.apply(sender, chain.context);
                ArgumentResult<Object> argumentResult = new ArgumentResult.Success<>(value, "");
                chain.append(new NodeResult(node, chain, argumentResult, argument.getSuggestionCallback()));
                // Add the default to the chain, and then carry on dealing with this node
            } else {
                // Still being asked to parse yet there's nothing left, syntax error.
                return new NodeResult(
                        node,
                        chain,
                        new ArgumentResult.SyntaxError<>("Not enough arguments", "", -1),
                        argument.getSuggestionCallback()
                );
            }
        }
        // Successfully matched this node's argument
        start = reader.cursor();
        if (!reader.hasRemaining()) start--; // This is needed otherwise the reader throws an AssertionError

        NodeResult error = null;
        for (Node child : node.next()) {
            NodeResult childResult = parseNode(sender, child, chain, reader);
            if (childResult.argumentResult instanceof ArgumentResult.Success<Object>) {
                // Assume that there is only one successful node for a given chain of arguments
                return childResult;
            } else {
                // Traverse through the node results to find the last
                // node with a valid argument
                final int childDepth = childResult.chain().depth();
                final boolean isDeeper = error != null && childDepth > error.chain().depth();

                if (childDepth > 0 && (error == null || isDeeper)) {
                    // If this is the base argument (e.g. "teleport" in /teleport) then
                    // do not report an argument to be incompatible, since the more
                    // correct thing would be to say that the command is unknown.
                    if (!(childResult.chain.size() == 2 && childResult.argumentResult instanceof ArgumentResult.IncompatibleType<?>)) {
                        // If the last successful result is null, throw an exception instead of having unintended behaviour
                        NodeResult lastSuccess = Objects.requireNonNull(childResult.chain().lastSuccessfulResult());
                        final Chain errorChain = lastSuccess.chain().fork();
                        errorChain.error = childResult.chain().error;
                        errorChain.errorCallback = childResult.chain().errorCallback;
                        errorChain.errorCondition = childResult.chain().errorCondition;
                        lastSuccess = new NodeResult(lastSuccess.node(), errorChain, lastSuccess.argumentResult(), lastSuccess.callback());
                        final SuggestionCallback deepestSuggestion = childResult.chain().suggestionCallback;

                        if (deepestSuggestion != null) {
                            errorChain.suggestionCallback = deepestSuggestion;
                            errorChain.suggestionCondition = childResult.chain().suggestionCondition;

                            lastSuccess = new NodeResult(
                                    lastSuccess.node(),
                                    errorChain,
                                    lastSuccess.argumentResult(),
                                    lastSuccess.callback()
                            );
                        }
                        error = lastSuccess;
                    }
                }
                reader.cursor(start);
            }
        }
        // None were successful. Either incompatible types, or syntax error. It doesn't matter to us, though
        // Try to execute this node
        CommandExecutor executor = nullSafeGetter(node.execution(), Graph.Execution::executor);
        if (executor == null) {
            // Stuck here with no executor
            if (error != null) {
                return error;
            } else {
                return chain.nodeResults.peekLast();
            }
        }

        if (reader.hasRemaining()) {
            // Trailing data is a syntax error
            // Can get to here if there's a default executor even if the user is still typing the command
            // So let's supply the next argument's suggestion callback if it exists
            Node returnNode = node;
            SuggestionCallback suggestionCallback = argument.getSuggestionCallback();
            List<Node> nextNodes = node.next();
            if (!nextNodes.isEmpty()) {
                returnNode = nextNodes.getFirst();
                suggestionCallback = returnNode.argument().getSuggestionCallback();
            }
            NodeResult nodeResult = new NodeResult(
                    returnNode,
                    error == null ? chain : error.chain,
                    new ArgumentResult.SyntaxError<>("Command has trailing data", "", -1),
                    suggestionCallback
            );
            if (error == null) {
                chain.suggestionCallback = suggestionCallback;
                chain.suggestionCondition = suggestionCallback == null ? null : chain.callbackCondition(returnNode);
            }
            // prevent duplicates from being added (Fixes CommandParseTest#singleCommandWithMultipleSyntax() failure)
            if (chain.getArgs().stream().noneMatch(arg -> arg.getId().equals(argument.getId()))) {
                chain.append(nodeResult);
            }
            return nodeResult;
        }

        // Command was successful!
        return chain.nodeResults.peekLast();
    }

    record UnknownCommandResult(CommandManager manager) implements Result.UnknownCommand {

        @Override
        public ExecutableCommand executable() {
            return new UnknownExecutableCmd(manager);
        }

        @Override
        public @Nullable Suggestion suggestion(CommandSender sender) {
            return null;
        }

        @Override
        public List<Argument<?>> args() {
            return null;
        }
    }

    sealed interface InternalKnownCommand extends Result.KnownCommand {
        CommandManager manager();
        String input();

        @Nullable CommandCondition condition();

        Map<String, ArgumentResult<Object>> arguments();

        CommandExecutor globalListener();

        @Nullable SuggestionCallback suggestionCallback();

        @Nullable CommandCondition suggestionCondition();

        @Override
        default @Nullable Suggestion suggestion(CommandSender sender) {
            manager().checkSender(sender);
            final SuggestionCallback callback = suggestionCallback();
            if (callback == null) return null;
            final int lastSpace = input().lastIndexOf(" ");
            final Suggestion suggestion = new Suggestion(input(), lastSpace + 2, input().length() - lastSpace - 1);
            final CommandContext context = createCommandContext(manager(), input(), arguments(), CommandContext.Purpose.SUGGESTION);
            if (suggestionCondition() != null && !suggestionCondition().canUse(sender, context)) return null;
            callback.apply(sender, context, suggestion);
            return suggestion;
        }
    }

    record InvalidCommand(CommandManager manager, String input, CommandCondition condition, @Nullable ArgumentCallback callback,
                          ArgumentResult.SyntaxError<?> error,
                          Map<String, ArgumentResult<Object>> arguments, CommandExecutor globalListener,
                          @Nullable SuggestionCallback suggestionCallback, @Nullable CommandCondition suggestionCondition, List<Argument<?>> args)
            implements InternalKnownCommand, Result.KnownCommand.Invalid {

        static InvalidCommand invalid(String input, Chain chain) {
            return new InvalidCommand(chain.context.commandManager(), input, chain.errorCondition != null ? chain.errorCondition : chain.mergedConditions(),
                    chain.errorCallback,
                    chain.error == null ? new ArgumentResult.SyntaxError<>("Command has trailing data.", input, -1)
                            : new ArgumentResult.SyntaxError<>(chain.error.getMessage(), chain.error.getInput(), chain.error.getErrorCode()),
                    chain.collectArguments(), chain.mergedGlobalExecutors(), chain.suggestionCallback, chain.suggestionCondition, chain.getArgs());
        }

        @Override
        public ExecutableCommand executable() {
            return new InvalidExecutableCmd(manager, condition, globalListener, callback, error, input, arguments);
        }
    }

    record ValidCommand(CommandManager manager, String input, CommandCondition condition, CommandExecutor executor,
                        Map<String, ArgumentResult<Object>> arguments,
                        CommandExecutor globalListener, @Nullable SuggestionCallback suggestionCallback,
                        @Nullable CommandCondition suggestionCondition, List<Argument<?>> args)
            implements InternalKnownCommand, Result.KnownCommand.Valid {

        static @Nullable ValidCommand defaultExecutor(String input, Chain chain) {
            CommandExecutor defaultExecutor = null;

            for (Iterator<NodeResult> it = chain.nodeResults.descendingIterator(); it.hasNext(); ) {
                final NodeResult node = it.next();
                defaultExecutor = node.chain().defaultExecutor;
                if (defaultExecutor != null) break;
            }

            if (defaultExecutor == null) return null;
            return new ValidCommand(chain.context.commandManager(), input, chain.mergedConditions(), defaultExecutor, chain.collectArguments(),
                    chain.mergedGlobalExecutors(), chain.suggestionCallback, chain.suggestionCondition, chain.getArgs());
        }

        static ValidCommand executor(String input, Chain chain, CommandExecutor executor) {
            return new ValidCommand(chain.context.commandManager(), input, chain.mergedConditions(), executor, chain.collectArguments(), chain.mergedGlobalExecutors(),
                    chain.suggestionCallback, chain.suggestionCondition, chain.getArgs());
        }

        @Override
        public ExecutableCommand executable() {
            return new ValidExecutableCmd(manager, condition, globalListener, executor, input, arguments);
        }
    }

    record UnknownExecutableCmd(CommandManager manager) implements ExecutableCommand {

        @Override
        public ExecutableCommand.Result execute(CommandSender sender) {
            manager.checkSender(sender);
            return ExecutionResultImpl.UNKNOWN;
        }
    }

    record ValidExecutableCmd(CommandManager manager, CommandCondition condition, CommandExecutor globalListener, CommandExecutor executor,
                              String input,
                              Map<String, ArgumentResult<Object>> arguments) implements ExecutableCommand {
        @Override
        public ExecutableCommand.Result execute(CommandSender sender) {
            manager.checkSender(sender);
            final CommandContext context = createCommandContext(manager, input, arguments);

            globalListener().apply(sender, context);

            if (condition != null && !condition.canUse(sender, context)) {
                return ExecutionResultImpl.PRECONDITION_FAILED;
            }
            try {
                executor().apply(sender, context);
                return new ExecutionResultImpl(ExecutableCommand.Result.Type.SUCCESS, context.getReturnData());
            } catch (Exception e) {
                manager.process().exceptionManager().handleException(e);
                return ExecutionResultImpl.EXECUTOR_EXCEPTION;
            }
        }
    }

    record InvalidExecutableCmd(CommandManager manager, CommandCondition condition, CommandExecutor globalListener, ArgumentCallback callback,
                                ArgumentResult.SyntaxError<?> error, String input,
                                Map<String, ArgumentResult<Object>> arguments) implements ExecutableCommand {
        @Override
        public ExecutableCommand.Result execute(CommandSender sender) {
            manager.checkSender(sender);
            final CommandContext context = createCommandContext(manager, input, arguments);
            globalListener().apply(sender, context);

            if (condition != null && !condition.canUse(sender, context)) {
                return ExecutionResultImpl.PRECONDITION_FAILED;
            }
            if (callback != null)
                callback.apply(sender, context, new ArgumentSyntaxException(error.message(), error.input(), error.code()));
            return ExecutionResultImpl.INVALID_SYNTAX;
        }
    }

    private static CommandContext createCommandContext(CommandManager manager, String input, Map<String, ArgumentResult<Object>> arguments) {
        return createCommandContext(manager, input, arguments, CommandContext.Purpose.EXECUTION);
    }

    private static CommandContext createCommandContext(CommandManager manager, String input, Map<String, ArgumentResult<Object>> arguments,
                                                        CommandContext.Purpose purpose) {
        final CommandContext context = new CommandContext(manager, input, purpose);
        for (var entry : arguments.entrySet()) {
            final String identifier = entry.getKey();
            final ArgumentResult<Object> value = entry.getValue();

            final Object argOutput = value instanceof ArgumentResult.Success<Object> success ? success.value() : null;
            final String argInput = value instanceof ArgumentResult.Success<Object> success ? success.input() : "";

            context.setArg(identifier, argOutput, argInput);
        }
        return context;
    }

    record ExecutionResultImpl(Type type, @Nullable CommandData commandData) implements ExecutableCommand.Result {
        static final ExecutableCommand.Result CANCELLED = new ExecutionResultImpl(Type.CANCELLED, null);
        static final ExecutableCommand.Result UNKNOWN = new ExecutionResultImpl(Type.UNKNOWN, null);
        static final ExecutableCommand.Result EXECUTOR_EXCEPTION = new ExecutionResultImpl(Type.EXECUTOR_EXCEPTION, null);
        static final ExecutableCommand.Result PRECONDITION_FAILED = new ExecutionResultImpl(Type.PRECONDITION_FAILED, null);
        static final ExecutableCommand.Result INVALID_SYNTAX = new ExecutionResultImpl(Type.INVALID_SYNTAX, null);
    }

    private record NodeResult(Node node, Chain chain, ArgumentResult<Object> argumentResult,
                              SuggestionCallback callback) {
        String name() {
            return node.argument().getId();
        }
    }

    static final class CommandStringReader {
        private final String input;
        private int cursor = 0;

        CommandStringReader(String input) {
            this.input = input;
        }

        boolean hasRemaining() {
            return cursor < input.length();
        }

        String readWord() {
            final String input = this.input;
            final int cursor = this.cursor;

            final int i = input.indexOf(' ', cursor);
            if (i == -1) {
                this.cursor = input.length() + 1;
                return input.substring(cursor);
            }
            final String read = input.substring(cursor, i);
            this.cursor += read.length() + 1;
            return read;
        }

        String readRemaining() {
            final String input = this.input;
            final String result = input.substring(cursor);
            this.cursor = input.length();
            return result;
        }

        int cursor() {
            return cursor;
        }

        void cursor(int cursor) {
            assert cursor >= 0 && cursor <= input.length();
            this.cursor = cursor;
        }
    }

    // ARGUMENT

    private static <T> ArgumentResult<T> parseArgument(CommandSender sender, CommandContext context, Argument<T> argument, CommandStringReader reader) {
        // Handle specific type without loop
        try {
            // Single word argument
            if (!argument.allowSpace()) {
                final String word = reader.readWord();
                return new ArgumentResult.Success<>(argument.parse(sender, context, word), word);
            }
            // Complete input argument
            if (argument.useRemaining()) {
                final String remaining = reader.readRemaining();
                return new ArgumentResult.Success<>(argument.parse(sender, context, remaining), remaining);
            }
        } catch (ArgumentSyntaxException e) {
            return new ArgumentResult.IncompatibleType<>(e);
        }
        // Bruteforce
        assert argument.allowSpace() && !argument.useRemaining();
        StringBuilder current = new StringBuilder(reader.readWord());
        while (true) {
            try {
                final String input = current.toString();
                return new ArgumentResult.Success<>(argument.parse(sender, context, input), input);
            } catch (ArgumentSyntaxException e) {
                if (!reader.hasRemaining()) return new ArgumentResult.IncompatibleType<>(e);
                current.append(" ");
                current.append(reader.readWord());
            }
        }
    }

    private sealed interface ArgumentResult<R> {
        record Success<T>(T value, String input)
                implements ArgumentResult<T> {
        }

        record IncompatibleType<T>(ArgumentSyntaxException exception)
                implements ArgumentResult<T> {
        }

        record SyntaxError<T>(String message, @Nullable String input, int code)
                implements ArgumentResult<T> {
        }
    }
}
