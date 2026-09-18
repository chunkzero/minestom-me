package net.minestom.demo.commands;

import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.SimpleCommand;
import net.minestom.server.entity.Player;

public class LegacyCommand extends SimpleCommand {
    public LegacyCommand() {
        super("test", "alias");
    }

    @Override
    public boolean process(CommandSender sender, CommandContext context, String command, String[] args) {
        if (!(sender instanceof Player)) return false;

        System.gc();
        sender.sendMessage("Explicit GC");
        return true;
    }

    @Override
    public boolean hasAccess(CommandSender sender, CommandContext context) {
        return true;
    }
}
