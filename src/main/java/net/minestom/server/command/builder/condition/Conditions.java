package net.minestom.server.command.builder.condition;

import net.minestom.server.command.CommandSender;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.entity.Player;

import java.util.Objects;

/**
 * Common command conditions
 */
public final class Conditions {
    /**
     * Will only execute if all command conditions succeed.
     */
    public static CommandCondition all(CommandCondition... conditions) {
        Objects.requireNonNull(conditions, "conditions cannot be null");
        for (CommandCondition condition : conditions) {
            Objects.requireNonNull(condition, "condition cannot be null");
        }
        return (sender, context) -> {
            for (CommandCondition condition : conditions) {
                if (!condition.canUse(sender, context)) {
                    return false;
                }
            }

            return true;
        };
    }

    /**
     * Will execute if one or more command conditions succeed.
     */
    public static CommandCondition any(CommandCondition... conditions) {
        Objects.requireNonNull(conditions, "conditions cannot be null");
        for (CommandCondition condition : conditions) {
            Objects.requireNonNull(condition, "condition cannot be null");
        }
        return (sender, context) -> {
            for (CommandCondition condition : conditions) {
                if (condition.canUse(sender, context)) {
                    return true;
                }
            }

            return false;
        };
    }

    /**
     * Will succeed if the command sender is a player.
     */
    public static boolean playerOnly(CommandSender sender, CommandContext context) {
        return sender instanceof Player;
    }

    /**
     * Will succeed if the command sender is the server console.
     */
    public static boolean consoleOnly(CommandSender sender, CommandContext context) {
        return sender instanceof ConsoleSender;
    }

    /**
     * Inverts the result of the given condition.
     */
    public static CommandCondition not(CommandCondition condition) {
        Objects.requireNonNull(condition, "condition cannot be null");
        return (sender, context) -> !condition.canUse(sender, context);
    }
}
