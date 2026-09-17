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
     *
     * @param sender the command sender
     * @param context the command context
     * @return whether the sender can use the command
     */
    boolean canUse(CommandSender sender, CommandContext context);
}
