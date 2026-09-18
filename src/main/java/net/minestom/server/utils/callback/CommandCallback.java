package net.minestom.server.utils.callback;

import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.CommandContext;

/**
 * Functional interface used by the {@link net.minestom.server.command.CommandManager}
 * to execute a callback if an unknown command is run.
 * You can set it with {@link net.minestom.server.command.CommandManager#setUnknownCommandCallback(CommandCallback)}.
 */
@FunctionalInterface
public interface CommandCallback {

    /**
     * Executed if an unknown command is run.
     *
     * @param sender  the command sender
     * @param context the command context
     */
    void apply(CommandSender sender, CommandContext context);

}
