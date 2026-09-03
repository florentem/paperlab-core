package io.papermc.paper.lab.command;

import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.lab.ghost.LabGhost;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /ghost} — переключатель режима наблюдателя.
 *
 * <p>Одна команда без аргументов, чтобы вешалась на бинд: посмотрел ферму — вышел.
 */
public final class LabGhostCommand {

    private LabGhostCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("ghost")
                .requires(source -> source.getEntity() instanceof ServerPlayer
                    && Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source))
                .executes(ctx -> {
                    final ServerPlayer player = (ServerPlayer) ctx.getSource().getEntity();
                    final boolean on = LabGhost.toggle(player);
                    ctx.getSource().sendSuccess(() -> Component.literal(on ? "ghost on" : "ghost off")
                        .withStyle(on ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY), false);
                    return 1;
                })
        );
    }
}
