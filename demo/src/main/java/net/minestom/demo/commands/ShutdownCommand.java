package net.minestom.demo.commands;

import net.minestom.server.ServerProcess;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;

/**
 * A simple shutdown command.
 */
public class ShutdownCommand extends Command {

    private final ServerProcess process;

    public ShutdownCommand(ServerProcess process) {
        super("shutdown");
        this.process = process;
        addSyntax(this::execute);
    }

    private void execute(CommandSender commandSender, CommandContext commandContext) {
        process.stop();
    }
}
