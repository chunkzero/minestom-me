package net.minestom.demo.commands;

import net.kyori.adventure.text.Component;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.condition.Conditions;
import net.minestom.server.command.builder.exception.ArgumentSyntaxException;
import net.minestom.server.entity.EntityProjectile;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.projectile.ArrowMeta;

import java.util.concurrent.ThreadLocalRandom;

public class ShootCommand extends Command {

    public ShootCommand() {
        super("shoot");
        setCondition(Conditions::playerOnly);
        setDefaultExecutor(ShootCommand::defaultExecutor);
        var typeArg = ArgumentType.Word("type").from("default", "spectral", "colored");
        setArgumentCallback(ShootCommand::onTypeError, typeArg);
        addSyntax(ShootCommand::onShootCommand, typeArg);
    }

    private static void defaultExecutor(CommandSender sender, CommandContext context) {
        sender.sendMessage(Component.text("Correct usage: shoot [default/spectral/colored]"));
    }

    private static void onTypeError(CommandSender sender, CommandContext context, ArgumentSyntaxException exception) {
        sender.sendMessage(Component.text("SYNTAX ERROR: '" + exception.getInput() + "' should be replaced by 'default', 'spectral' or 'colored'"));
    }

    private static void onShootCommand(CommandSender sender, CommandContext context) {
        Player player = (Player) sender;
        String mode = context.get("type");
        EntityType entityType;
        switch (mode) {
            case "default", "colored" -> entityType = EntityType.ARROW;
            case "spectral" -> entityType = EntityType.SPECTRAL_ARROW;
            default -> {
                return;
            }
        }
        var builder = EntityProjectile.builder(player, entityType);
        if (mode.equals("colored")) {
            builder.initialize(projectile -> projectile.editEntityMeta(ArrowMeta.class,
                    meta -> meta.setColor(ThreadLocalRandom.current().nextInt())));
        }
        var pos = player.getPosition().add(0D, player.getEyeHeight(), 0D);
        //noinspection ConstantConditions - It should be impossible to execute a command without being in an instance
        var projectile = builder.spawn(player.getInstance(), pos).join();
        var dir = pos.direction().mul(30D);
        pos = pos.add(dir);
        projectile.shoot(pos, 1D, 0D);
    }
}
