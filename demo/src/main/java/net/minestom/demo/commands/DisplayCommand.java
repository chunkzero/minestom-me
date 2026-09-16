package net.minestom.demo.commands;

import net.kyori.adventure.text.Component;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.BlockDisplayMeta;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.utils.time.TimeUnit;

public class DisplayCommand extends Command {

    public DisplayCommand() {
        super("display");

        var follow = ArgumentType.Literal("follow");

        addSyntax(DisplayCommand::spawnItem, ArgumentType.Literal("item"));
        addSyntax(DisplayCommand::spawnBlock, ArgumentType.Literal("block"));
        addSyntax(DisplayCommand::spawnText, ArgumentType.Literal("text"));

        addSyntax(DisplayCommand::spawnItem, ArgumentType.Literal("item"), follow);
        addSyntax(DisplayCommand::spawnBlock, ArgumentType.Literal("block"), follow);
        addSyntax(DisplayCommand::spawnText, ArgumentType.Literal("text"), follow);
    }

    public static void spawnItem(CommandSender sender, CommandContext context) {
        if (!(sender instanceof Player player))
            return;

        var entity = Entity.builder(EntityType.ITEM_DISPLAY)
                .initialize(display -> display.editEntityMeta(ItemDisplayMeta.class, meta -> {
                    meta.setTransformationInterpolationDuration(20);
                    meta.setItemStack(ItemStack.of(Material.STICK));
                }))
                .spawn(player.getInstance(), player.getPosition()).join();

        if (context.has("follow")) {
            startSmoothFollow(entity, player);
        }
    }

    public static void spawnBlock(CommandSender sender, CommandContext context) {
        if (!(sender instanceof Player player))
            return;

        var entity = Entity.builder(EntityType.BLOCK_DISPLAY)
                .initialize(display -> display.editEntityMeta(BlockDisplayMeta.class, meta -> {
                    meta.setTransformationInterpolationDuration(20);
                    meta.setBlockState(Block.ORANGE_CANDLE_CAKE);
                }))
                .spawn(player.getInstance(), player.getPosition()).join();

        if (context.has("follow")) {
            startSmoothFollow(entity, player);
        }
    }

    public static void spawnText(CommandSender sender, CommandContext context) {
        if (!(sender instanceof Player player))
            return;

        var entity = Entity.builder(EntityType.TEXT_DISPLAY)
                .initialize(display -> display.editEntityMeta(TextDisplayMeta.class, meta -> {
                    meta.setTransformationInterpolationDuration(20);
                    meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
                    meta.setText(Component.text("Hello, world!"));
                }))
                .spawn(player.getInstance(), player.getPosition()).join();

        if (context.has("follow")) {
            startSmoothFollow(entity, player);
        }
    }

    private static void startSmoothFollow(Entity entity, Player player) {
//        entity.setCustomName(Component.text("MY CUSTOM NAME"));
//        entity.setCustomNameVisible(true);
        entity.process().scheduler().buildTask(() -> {
            var meta = (AbstractDisplayMeta) entity.getEntityMeta();
            meta.setNotifyAboutChanges(false);
            meta.setTransformationInterpolationStartDelta(1);
            meta.setTransformationInterpolationDuration(20);
//            meta.setPosRotInterpolationDuration(20);
//            entity.teleport(player.getPosition());
//            meta.setScale(new Vec(5, 5, 5));
            meta.setTranslation(player.getPosition().sub(entity.getPosition()));
            meta.setNotifyAboutChanges(true);
        }).delay(20, TimeUnit.SERVER_TICK).repeat(20, TimeUnit.SERVER_TICK).schedule();
    }
}
