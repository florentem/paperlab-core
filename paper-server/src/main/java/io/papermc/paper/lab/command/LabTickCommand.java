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
 * Additions to vanilla {@code /tick}.
 *
 * <p>In 26.2 the command already exists in vanilla: {@code query}, {@code rate},
 * {@code step}, {@code sprint}, {@code freeze}, {@code unfreeze}. Only a toggle is
 * missing: vanilla {@code freeze} and {@code unfreeze} set the state absolutely, so they
 * cannot share a keybind. Carpet uses a toggle for exactly this reason.
 *
 * <p>So we <b>append</b> nodes to the already registered tree without touching vanilla
 * behaviour. There is no separate {@code /labtick} any more: commands that also exist in
 * Carpet must carry Carpet's name, or muscle memory stops working.
 *
 * <pre>
 *
 * /tick toggle          freeze / unfreeze with one command — for a keybind
 * /tick warp &lt;time&gt;     alias of sprint (the familiar name from Carpet TIS)
 * /tick warp stop
 * </pre>
 */
public final class LabTickCommand {

    private LabTickCommand() {
    }

    /**
     * Called <b>after</b> {@code TickCommand.register}: the {@code tick} node must already
     * exist.
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
        if (io.papermc.paper.lab.zone.LabTickZones.isFocused(source)) {
            return io.papermc.paper.lab.zone.LabTickZones.handleToggle(source);
        }
        if (io.papermc.paper.lab.zone.LabTickZones.isEnabled() && source.isPlayer()
            && !source.getBukkitSender().hasPermission("paperlab.tick.global")) {
            source.sendFailure(Component.literal("Missing permission: paperlab.tick.global"));
            return 0;
        }
        final ServerTickRateManager manager = io.papermc.paper.lab.tick.LabPerWorldTick.getManager(source);
        final boolean freeze = !manager.isFrozen();
        if (freeze) {
            // Same order as vanilla setFreeze: stop sprinting and stepping first.
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
        if (io.papermc.paper.lab.zone.LabTickZones.isFocused(source)) {
            return io.papermc.paper.lab.zone.LabTickZones.handleSprint(source, ticks);
        }
        if (io.papermc.paper.lab.zone.LabTickZones.isEnabled() && source.isPlayer()
            && !source.getBukkitSender().hasPermission("paperlab.tick.global")) {
            source.sendFailure(Component.literal("Missing permission: paperlab.tick.global"));
            return 0;
        }
        final ServerTickRateManager manager = io.papermc.paper.lab.tick.LabPerWorldTick.getManager(source);
        manager.requestGameToSprint(ticks);
        source.sendSuccess(() -> Component.literal("warp " + ticks + "t")
            .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    private static int stopWarp(final CommandSourceStack source) {
        if (io.papermc.paper.lab.zone.LabTickZones.isFocused(source)) {
            return io.papermc.paper.lab.zone.LabTickZones.handleStopSprinting(source);
        }
        if (io.papermc.paper.lab.zone.LabTickZones.isEnabled() && source.isPlayer()
            && !source.getBukkitSender().hasPermission("paperlab.tick.global")) {
            source.sendFailure(Component.literal("Missing permission: paperlab.tick.global"));
            return 0;
        }
        final boolean stopped = io.papermc.paper.lab.tick.LabPerWorldTick.getManager(source).stopSprinting();
        source.sendSuccess(() -> Component.literal(stopped ? "warp stop" : "was not running")
            .withStyle(ChatFormatting.DARK_GRAY), false);
        return stopped ? 1 : 0;
    }
}
