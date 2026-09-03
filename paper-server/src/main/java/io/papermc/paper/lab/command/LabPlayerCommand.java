package io.papermc.paper.lab.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.lab.bot.LabBot;
import io.papermc.paper.lab.bot.LabBotRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /player} — боты, синтаксис и поведение как в Carpet.
 *
 * <pre>
 * /player &lt;name&gt; spawn [at &lt;x y z&gt;] [facing &lt;yaw pitch&gt;] [in &lt;dim&gt;] [&lt;gamemode&gt;]
 * /player &lt;name&gt; kill
 * /player list
 * </pre>
 *
 * Бот наследует от вызывающего игрока позицию, поворот, измерение, режим игры и полёт.
 */
public final class LabPlayerCommand {

    private LabPlayerCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("player")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                            LabBotRegistry.bots().stream().map(LabBot::labName).toList(), builder))
                        .then(Commands.literal("kill").executes(ctx -> kill(ctx)))
                        .then(
                            Commands.literal("spawn")
                                .executes(ctx -> spawn(ctx, null, null, null, null))
                                .then(
                                    Commands.literal("at")
                                        .then(
                                            Commands.argument("position", Vec3Argument.vec3())
                                                .executes(ctx -> spawn(ctx,
                                                    Vec3Argument.getVec3(ctx, "position"), null, null, null))
                                                .then(
                                                    Commands.literal("facing").then(
                                                        Commands.argument("direction", RotationArgument.rotation())
                                                            .executes(ctx -> spawn(ctx,
                                                                Vec3Argument.getVec3(ctx, "position"),
                                                                RotationArgument.getRotation(ctx, "direction")
                                                                    .getRotation(ctx.getSource()),
                                                                null, null))
                                                            .then(
                                                                Commands.literal("in").then(
                                                                    Commands.argument("dimension", DimensionArgument.dimension())
                                                                        .executes(ctx -> spawn(ctx,
                                                                            Vec3Argument.getVec3(ctx, "position"),
                                                                            RotationArgument.getRotation(ctx, "direction")
                                                                                .getRotation(ctx.getSource()),
                                                                            DimensionArgument.getDimension(ctx, "dimension"),
                                                                            null))
                                                                )
                                                            )
                                                    )
                                                )
                                        )
                                )
                                .then(
                                    Commands.argument("gamemode", GameModeArgument.gameMode())
                                        .executes(ctx -> spawn(ctx, null, null, null,
                                            GameModeArgument.getGameMode(ctx, "gamemode")))
                                )
                        )
                )
        );
    }

    private static int spawn(final CommandContext<CommandSourceStack> ctx,
                             final Vec3 posArg,
                             final Vec2 rotArg,
                             final ServerLevel levelArg,
                             final GameType modeArg) {
        final CommandSourceStack source = ctx.getSource();
        final String name = StringArgumentType.getString(ctx, "name");

        // Наследование от вызывающего — как в Carpet: позиция, поворот, измерение,
        // режим игры и полёт берутся у отправителя, если не заданы явно.
        final Vec3 pos = posArg != null ? posArg : source.getPosition();
        final Vec2 rot = rotArg != null ? rotArg : source.getRotation();
        final ServerLevel level = levelArg != null ? levelArg : source.getLevel();

        GameType mode = GameType.CREATIVE;
        boolean flying = false;
        if (source.getEntity() instanceof final ServerPlayer sender) {
            mode = sender.gameMode.getGameModeForPlayer();
            flying = sender.getAbilities().flying;
        }
        if (modeArg != null) {
            mode = modeArg;
        }
        // Спектатор без полёта провалится из мира; выживание с полётом полетит само.
        if (mode == GameType.SPECTATOR) {
            flying = true;
        } else if (mode.isSurvival()) {
            flying = false;
        }

        final String error = LabBotRegistry.spawn(
            source.getServer(), name, level, pos, rot.y, rot.x, mode, flying);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        return 1;
    }

    private static int kill(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final String name = StringArgumentType.getString(ctx, "name");
        if ("all".equalsIgnoreCase(name) || "*".equals(name)) {
            final int removed = LabBotRegistry.removeAll();
            source.sendSuccess(() -> Component.literal("убрано: " + removed)
                .withStyle(ChatFormatting.DARK_GRAY), false);
            return removed;
        }
        if (!LabBotRegistry.remove(name)) {
            source.sendFailure(Component.literal("нет бота " + name));
            return 0;
        }
        return 1;
    }

    private static int list(final CommandSourceStack source) {
        if (LabBotRegistry.count() == 0) {
            source.sendSuccess(() -> Component.literal("ботов нет").withStyle(ChatFormatting.DARK_GRAY), false);
            return 0;
        }
        for (final LabBot bot : LabBotRegistry.bots()) {
            final ResourceKey<Level> dim = bot.level().dimension();
            source.sendSuccess(() -> Component.literal(String.format(
                "%s  %s  %.0f %.0f %.0f  %s",
                bot.labName(),
                dim.identifier().getPath(),
                bot.getX(), bot.getY(), bot.getZ(),
                bot.gameMode.getGameModeForPlayer().getName())).withStyle(ChatFormatting.GRAY), false);
        }
        return LabBotRegistry.count();
    }
}
