package net.minestom.demo.commands;

import net.minestom.server.ServerProcess;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentEnum;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.minecraft.registry.ArgumentEntityType;
import net.minestom.server.command.builder.condition.Conditions;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityBuilder;
import net.minestom.server.entity.EntityCreature;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.utils.location.RelativeVec;

import java.util.function.BiFunction;

public class SummonCommand extends Command {

    private final ArgumentEntityType entity;
    private final Argument<RelativeVec> pos;
    private final Argument<EntityClass> entityClass;

    public SummonCommand() {
        super("summon");
        setCondition(Conditions::playerOnly);

        entity = ArgumentType.EntityType("entity type");
        pos = ArgumentType.RelativeVec3("pos").setDefaultValue(() -> new RelativeVec(
                new Vec(0, 0, 0),
                RelativeVec.CoordinateType.RELATIVE,
                true, true, true
        ));
        entityClass = ArgumentType.Enum("class", EntityClass.class)
                .setFormat(ArgumentEnum.Format.LOWER_CASED)
                .setDefaultValue(EntityClass.CREATURE);
        addSyntax(this::execute, entity, pos, entityClass);
        setDefaultExecutor((sender, _) -> sender.sendMessage("Usage: /summon <type> <x> <y> <z> <class>"));
    }

    private void execute(CommandSender commandSender, CommandContext commandContext) {
        if (!(commandSender instanceof Player player)) return;
        //noinspection ConstantConditions - One couldn't possibly execute a command without being in an instance
        commandContext.get(entityClass).builder(commandContext.get(entity))
                .spawn(player.getInstance(), commandContext.get(pos).fromSender(player)).join();
    }

    @SuppressWarnings("unused")
    enum EntityClass {
        BASE(Entity::new),
        LIVING(LivingEntity::new),
        CREATURE(EntityCreature::new);

        private final BiFunction<ServerProcess, EntityType, ? extends Entity> factory;

        EntityClass(BiFunction<ServerProcess, EntityType, ? extends Entity> factory) {
            this.factory = factory;
        }

        public EntityBuilder<? extends Entity> builder(EntityType type) {
            return Entity.builder(type, factory);
        }
    }
}
