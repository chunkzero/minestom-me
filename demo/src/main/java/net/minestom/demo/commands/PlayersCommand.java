package net.minestom.demo.commands;

import net.kyori.adventure.text.Component;
import net.minestom.server.ServerProcess;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;

import java.util.List;

public class PlayersCommand extends Command {

    private final ServerProcess process;

    public PlayersCommand(ServerProcess process) {
        super("players");
        this.process = process;
        setDefaultExecutor(this::usage);
    }

    private void usage(CommandSender sender, CommandContext context) {
        final var players = List.copyOf(process.connection().getOnlinePlayers());
        final int playerCount = players.size();
        sender.sendMessage(Component.text("Total players: " + playerCount));

        final int limit = 15;
        for (int i = 0; i < Math.min(limit, playerCount); i++) {
            final var player = players.get(i);
            sender.sendMessage(Component.text(player.getUsername()));
        }

        if (playerCount > limit) sender.sendMessage(Component.text("..."));
    }

}
