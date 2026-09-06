package io.papermc.paper.lab.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.core.BlockPos;
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

        tick.addChild(Commands.literal("dump")
            .requires(source -> io.papermc.paper.lab.rules.LabRuleState.tickCommandCarpetfied
                && source.getBukkitSender().hasPermission("paperlab.tick"))
            .executes(ctx -> dumpDefault(ctx.getSource(), 100))
            .then(Commands.literal("status").executes(ctx -> dumpStatus(ctx.getSource())))
            .then(Commands.literal("stop").executes(ctx -> dumpStop(ctx.getSource())))
            .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                .executes(ctx -> dumpDefault(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ticks"))))
            .then(Commands.literal("zone")
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(ctx -> dumpZone(ctx.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"), 100))
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                        .executes(ctx -> dumpZone(ctx.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"), IntegerArgumentType.getInteger(ctx, "ticks"))))))
            .then(Commands.literal("area")
                .then(Commands.argument("x1", IntegerArgumentType.integer())
                    .then(Commands.argument("y1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                            .then(Commands.argument("x2", IntegerArgumentType.integer())
                                .then(Commands.argument("y2", IntegerArgumentType.integer())
                                    .then(Commands.argument("z2", IntegerArgumentType.integer())
                                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                                            .executes(ctx -> dumpArea(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "x1"),
                                                IntegerArgumentType.getInteger(ctx, "y1"),
                                                IntegerArgumentType.getInteger(ctx, "z1"),
                                                IntegerArgumentType.getInteger(ctx, "x2"),
                                                IntegerArgumentType.getInteger(ctx, "y2"),
                                                IntegerArgumentType.getInteger(ctx, "z2"),
                                                IntegerArgumentType.getInteger(ctx, "ticks")))))))))))
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

    private static int dumpStatus(final CommandSourceStack source) {
        final String status = io.papermc.paper.lab.dump.ZoneDumpManager.getStatus();
        source.sendSuccess(() -> Component.literal(status).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int dumpStop(final CommandSourceStack source) {
        final String msg = io.papermc.paper.lab.dump.ZoneDumpManager.stopDump();
        source.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int dumpDefault(final CommandSourceStack source, final int ticks) {
        if (source.isPlayer()) {
            final io.papermc.paper.lab.zone.LabTickZone focused = io.papermc.paper.lab.zone.LabTickZones.getFocusedZone(source);
            if (focused != null) {
                final String res = io.papermc.paper.lab.dump.ZoneDumpManager.startZoneDump(focused, ticks);
                source.sendSuccess(() -> Component.literal(res).withStyle(ChatFormatting.GREEN), true);
                return 1;
            }
        }
        final BlockPos center = BlockPos.containing(source.getPosition());
        final BlockPos min = center.offset(-16, -16, -16);
        final BlockPos max = center.offset(16, 16, 16);
        final String worldKey = io.papermc.paper.lab.zone.LabTickZones.resolveWorldKey(source.getLevel());
        final String res = io.papermc.paper.lab.dump.ZoneDumpManager.startAreaDump(worldKey, min, max, ticks);
        source.sendSuccess(() -> Component.literal(res).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int dumpZone(final CommandSourceStack source, final String name, final int ticks) {
        final io.papermc.paper.lab.zone.LabTickZone zone = io.papermc.paper.lab.zone.LabTickZones.getZone(name);
        if (zone == null) {
            source.sendFailure(Component.literal("Unknown zone: " + name));
            return 0;
        }
        final String res = io.papermc.paper.lab.dump.ZoneDumpManager.startZoneDump(zone, ticks);
        source.sendSuccess(() -> Component.literal(res).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int dumpArea(final CommandSourceStack source, final int x1, final int y1, final int z1, final int x2, final int y2, final int z2, final int ticks) {
        final BlockPos min = new BlockPos(x1, y1, z1);
        final BlockPos max = new BlockPos(x2, y2, z2);
        final String worldKey = io.papermc.paper.lab.zone.LabTickZones.resolveWorldKey(source.getLevel());
        final String res = io.papermc.paper.lab.dump.ZoneDumpManager.startAreaDump(worldKey, min, max, ticks);
        source.sendSuccess(() -> Component.literal(res).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}
