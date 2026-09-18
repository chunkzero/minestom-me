package net.minestom.server.command.builder.condition;

import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.CommandContext;

/**
 * Checks whether a sender can use a command or syntax.
 */
@FunctionalInterface
public interface CommandCondition {
    /**
     * The context supplies the executing manager and process. Its input is empty when
     * checking command visibility for a declaration packet, otherwise it is the command input.
     * Conditions also run for suggestion requests with the currently typed input. Use
     * {@link CommandContext#purpose()} to distinguish suggestions and declarations from execution
     * before sending feedback to the sender. Arguments may be incomplete when checking
     * suggestions or argument error callbacks; a callback on a shared argument prefix is allowed
     * when at least one applicable syntax permits it.
     *
     * @param sender the command sender
     * @param context the command context
     * @return whether the sender can use the command
     */
    boolean canUse(CommandSender sender, CommandContext context);
}
