package io.papermc.paper.lab.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerTickRateManager;

/**
 * Дополнения к ванильному {@code /tick}.
 *
 * <p>В 26.2 команда уже есть в ваниле: {@code query}, {@code rate}, {@code step},
 * {@code sprint}, {@code freeze}, {@code unfreeze}. Не хватает только переключателя:
 * ванильные {@code freeze} и {@code unfreeze} задают состояние жёстко, поэтому на один
 * бинд их не повесить. Carpet использует именно переключатель.
 *
 * <p>Поэтому мы <b>дописываем</b> узлы в уже зарегистрированное дерево, не трогая
 * поведение ванильных. Своей команды {@code /labtick} больше нет: команды, которые есть
 * и в Carpet, должны называться так же, как там, иначе мышечная память не работает.
 *
 * <pre>
 *
 * /tick toggle          заморозить / разморозить одной командой — для бинда
 * /tick warp &lt;время&gt;    алиас sprint (привычное имя из Carpet TIS)
 * /tick warp stop
 * </pre>
 */
public final class LabTickCommand {

    private LabTickCommand() {
    }

    /**
     * Вызывается <b>после</b> {@code TickCommand.register}: узел {@code tick} уже
     * должен существовать.
     */
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        final CommandNode<CommandSourceStack> tick = dispatcher.getRoot().getChild("tick");
        if (!(tick instanceof LiteralCommandNode)) {
            return;
        }

        tick.addChild(Commands.literal("toggle")
            .requires(source -> io.papermc.paper.lab.rules.LabRuleState.tickCommandCarpetfied
                && source.getBukkitSender().hasPermission("paperlab.tick"))
            .executes(ctx -> toggle(ctx.getSource()))
            .build());

        tick.addChild(Commands.literal("warp")
            .requires(source -> io.papermc.paper.lab.rules.LabRuleState.tickCommandCarpetfied
                && source.getBukkitSender().hasPermission("paperlab.tick"))
            .then(Commands.literal("stop").executes(ctx -> stopWarp(ctx.getSource())))
            .then(Commands.argument("time", TimeArgument.time(1))
                .executes(ctx -> warp(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "time"))))
            .build());
    }

    private static int toggle(final CommandSourceStack source) {
        final ServerTickRateManager manager = source.getServer().tickRateManager();
        final boolean freeze = !manager.isFrozen();
        if (freeze) {
            // Порядок как в ванильном setFreeze: спринт и пошаговый режим сначала гасим.
            if (manager.isSprinting()) {
                manager.stopSprinting();
            }
            if (manager.isSteppingForward()) {
                manager.stopStepping();
            }
        }
        manager.setFrozen(freeze);
        source.sendSuccess(() -> Component.literal(freeze ? "frozen" : "running")
            .withStyle(freeze ? ChatFormatting.AQUA : ChatFormatting.GREEN), true);
        return 1;
    }

    private static int warp(final CommandSourceStack source, final int ticks) {
        final ServerTickRateManager manager = source.getServer().tickRateManager();
        manager.requestGameToSprint(ticks);
        source.sendSuccess(() -> Component.literal("warp " + ticks + "t")
            .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    private static int stopWarp(final CommandSourceStack source) {
        final boolean stopped = source.getServer().tickRateManager().stopSprinting();
        source.sendSuccess(() -> Component.literal(stopped ? "warp stop" : "не был запущен")
            .withStyle(ChatFormatting.DARK_GRAY), false);
        return stopped ? 1 : 0;
    }
}
