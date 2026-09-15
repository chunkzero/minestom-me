package net.minestom.demo.commands;

import net.minestom.server.ServerProcess;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A simple shutdown command.
 */
public class SaveCommand extends Command {

    private final ServerProcess process;

    public SaveCommand(ServerProcess process) {
        super("save");
        this.process = process;
        addSyntax(this::execute);
    }

    private void execute(CommandSender commandSender, CommandContext commandContext) {
        for(var instance : process.instance().getInstances()) {
            CompletableFuture<Void> instanceSave = instance.saveInstance().thenCompose(_ -> instance.saveChunksToStorage());
            try {
                instanceSave.get();
            } catch (InterruptedException | ExecutionException e) {
                process.exception().handleException(e);
            }
        }
        commandSender.sendMessage("Saving done!");
    }
}
