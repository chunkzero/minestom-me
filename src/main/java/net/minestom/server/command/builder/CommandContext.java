package net.minestom.server.command.builder;

import net.minestom.server.ServerProcess;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.utils.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Class used to retrieve argument data in a {@link CommandExecutor}.
 * <p>
 * All id are the one specified in the {@link Argument} constructor.
 * <p>
 * All methods are @{@link NotNull} in the sense that you should not have to verify their validity since if the syntax
 * is called, it means that all of its arguments are correct. Be aware that trying to retrieve an argument not present
 * in the syntax will result in a {@link NullPointerException}.
 */
public class CommandContext {

    private final CommandManager commandManager;
    private final String input;
    private final String commandName;
    private final Purpose purpose;
    protected Map<String, Object> args;
    protected Map<String, String> rawArgs;
    private CommandData returnData;

    public CommandContext(CommandManager commandManager, String input) {
        this(commandManager, input, Purpose.EXECUTION);
    }

    public CommandContext(CommandManager commandManager, String input, Purpose purpose) {
        this.commandManager = Objects.requireNonNull(commandManager);
        this.input = input;
        this.commandName = input.split(StringUtils.SPACE, 0)[0];
        this.purpose = Objects.requireNonNull(purpose);
        this.args = new HashMap<>();
        this.rawArgs = new HashMap<>();
    }

    private CommandContext(CommandContext source) {
        this.commandManager = source.commandManager;
        this.input = source.input;
        this.commandName = source.commandName;
        this.purpose = source.purpose;
        this.args = new HashMap<>(source.args);
        this.rawArgs = new HashMap<>(source.rawArgs);
    }

    public ServerProcess process() {
        return commandManager.process();
    }

    public CommandManager commandManager() {
        return commandManager;
    }

    public CommandContext fork() {
        return new CommandContext(this);
    }

    public Purpose purpose() {
        return purpose;
    }

    /**
     * The operation using this context. Parsing can precede either execution or suggestions.
     */
    public enum Purpose {
        PARSING, EXECUTION, SUGGESTION, DECLARATION
    }

    public String getInput() {
        return input;
    }

    public String getCommandName() {
        return commandName;
    }

    public <T> T get(Argument<T> argument) {
        return get(argument.getId());
    }

    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    public <T> T get(String identifier) {
        return (T) args.get(identifier);
    }

    public <T> T getOrDefault(Argument<T> argument, T defaultValue) {
        return getOrDefault(argument.getId(), defaultValue);
    }

    public <T> T getOrDefault(String identifier, T defaultValue) {
        T value;
        return (value = get(identifier)) != null ? value : defaultValue;
    }

    public boolean has(Argument<?> argument) {
        return args.containsKey(argument.getId());
    }

    public boolean has(String identifier) {
        return args.containsKey(identifier);
    }

    public @Nullable CommandData getReturnData() {
        return returnData;
    }

    public void setReturnData(@Nullable CommandData returnData) {
        this.returnData = returnData;
    }

    public Map<String, Object> getMap() {
        return args;
    }

    public void copy(CommandContext context) {
        if (commandManager != context.commandManager)
            throw new IllegalArgumentException("Command context belongs to another manager");
        this.args = new HashMap<>(context.args);
        this.rawArgs = new HashMap<>(context.rawArgs);
    }

    public String getRaw(Argument<?> argument) {
        return rawArgs.get(argument.getId());
    }

    public String getRaw(String identifier) {
        return rawArgs.get(identifier);
    }

    public void setArg(String id, Object value, String rawInput) {
        this.args.put(id, value);
        this.rawArgs.put(id, rawInput);
    }

    protected void clear() {
        this.args.clear();
    }

    protected void retrieveDefaultValues(@Nullable Map<String, Supplier<Object>> defaultValuesMap) {
        if (defaultValuesMap == null) return;
        for (var entry : defaultValuesMap.entrySet()) {
            final String key = entry.getKey();
            if (!args.containsKey(key)) {
                final var supplier = entry.getValue();
                this.args.put(key, supplier.get());
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CommandContext that)) return false;
        return commandManager == that.commandManager && purpose == that.purpose && Objects.equals(input, that.input) &&
                Objects.equals(commandName, that.commandName) &&
                Objects.equals(args, that.args) &&
                Objects.equals(rawArgs, that.rawArgs) &&
                Objects.equals(returnData, that.returnData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commandManager, input, commandName, purpose, args, rawArgs, returnData);
    }
}
