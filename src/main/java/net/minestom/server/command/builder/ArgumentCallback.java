package net.minestom.server.command.builder;

import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.exception.ArgumentSyntaxException;

/**
 * Callback executed when an error is found within the {@link Argument}.
 * An applicable argument error callback takes precedence over the command's default executor.
 * Command and syntax conditions must permit the callback; the result remains invalid syntax.
 */
@FunctionalInterface
public interface ArgumentCallback {

    /**
     * Executed when an error is found.
     *
     * @param sender    the sender which executed the command
     * @param context   the executing manager's context and successfully parsed arguments
     * @param exception the exception containing the message, input and error code related to the issue
     */
    void apply(CommandSender sender, CommandContext context, ArgumentSyntaxException exception);
}
