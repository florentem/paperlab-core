package io.papermc.paper.lab.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.lab.bot.LabAction;
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
import org.checkerframework.checker.nullness.qual.Nullable;

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

    /**
     * Зарегистрированный узел. Нужен плагину: он вешает {@code /carpet player}
     * перенаправлением сюда, чтобы весь набор инструментов табался из одной точки,
     * а дерево при этом было ровно одно.
     *
     * <p>{@code null}, пока команда не зарегистрирована, — плагин это проверяет.
     */
    public static volatile @Nullable LiteralCommandNode<CommandSourceStack> node;

    private LabPlayerCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        node = dispatcher.register(
            Commands.literal("player")
                // Право bukkit, а не уровень оператора: так узел виден LuckPerms
                // наравне с остальными командами инструментария (paperlab.player).
                .requires(source -> io.papermc.paper.lab.rules.LabRuleState.playerCommandEnabled
                    && source.getBukkitSender().hasPermission("paperlab.player"))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                            LabBotRegistry.bots().stream().map(LabBot::labName).toList(), builder))
                        .then(Commands.literal("kill").executes(ctx -> kill(ctx)))
                        .then(Commands.literal("stop").executes(ctx -> stop(ctx)))
                        .then(Commands.literal("ghost").executes(ctx -> ghost(ctx)))
                        .then(action("attack", LabAction.ATTACK))
                        .then(action("use", LabAction.USE))
                        .then(action("jump", LabAction.JUMP))
                        .then(action("drop", LabAction.DROP_ITEM))
                        .then(action("dropStack", LabAction.DROP_STACK))
                        .then(action("swapHands", LabAction.SWAP_HANDS))
                        .then(Commands.literal("mount")
                            .executes(ctx -> mount(ctx, true))
                            .then(Commands.literal("anything").executes(ctx -> mount(ctx, false))))
                        .then(Commands.literal("dismount").executes(ctx -> dismount(ctx)))
                        .then(Commands.literal("respawn")
                            .executes(ctx -> respawn(ctx, null))
                            .then(Commands.literal("on").executes(ctx -> respawn(ctx, Boolean.TRUE)))
                            .then(Commands.literal("off").executes(ctx -> respawn(ctx, Boolean.FALSE))))
                        .then(
                            Commands.literal("move")
                                .then(Commands.literal("forward").executes(ctx -> move(ctx, 1.0F, 0.0F)))
                                .then(Commands.literal("back").executes(ctx -> move(ctx, -1.0F, 0.0F)))
                                .then(Commands.literal("left").executes(ctx -> move(ctx, 0.0F, 1.0F)))
                                .then(Commands.literal("right").executes(ctx -> move(ctx, 0.0F, -1.0F)))
                                .then(Commands.literal("stop").executes(ctx -> move(ctx, 0.0F, 0.0F)))
                        )
                        .then(
                            Commands.literal("turn")
                                .then(Commands.literal("left").executes(ctx -> turn(ctx, -90.0F, 0.0F)))
                                .then(Commands.literal("right").executes(ctx -> turn(ctx, 90.0F, 0.0F)))
                                .then(Commands.literal("back").executes(ctx -> turn(ctx, 180.0F, 0.0F)))
                                .then(
                                    Commands.argument("rotation", RotationArgument.rotation())
                                        .executes(ctx -> {
                                            final Vec2 rot = RotationArgument.getRotation(ctx, "rotation")
                                                .getRotation(ctx.getSource());
                                            return turn(ctx, rot.y, rot.x);
                                        })
                                )
                        )
                        .then(Commands.literal("sneak").executes(ctx -> sneak(ctx, true)))
                        .then(Commands.literal("unsneak").executes(ctx -> sneak(ctx, false)))
                        .then(Commands.literal("sprint").executes(ctx -> sprint(ctx, true)))
                        .then(Commands.literal("unsprint").executes(ctx -> sprint(ctx, false)))
                        .then(
                            Commands.literal("hotbar").then(
                                Commands.argument("slot", IntegerArgumentType.integer(1, 9))
                                    .executes(ctx -> hotbar(ctx, IntegerArgumentType.getInteger(ctx, "slot")))
                            )
                        )
                        .then(
                            Commands.literal("look")
                                .then(Commands.literal("north").executes(ctx -> look(ctx, 180.0F, 0.0F)))
                                .then(Commands.literal("south").executes(ctx -> look(ctx, 0.0F, 0.0F)))
                                .then(Commands.literal("east").executes(ctx -> look(ctx, -90.0F, 0.0F)))
                                .then(Commands.literal("west").executes(ctx -> look(ctx, 90.0F, 0.0F)))
                                .then(Commands.literal("up").executes(ctx -> look(ctx, null, -90.0F)))
                                .then(Commands.literal("down").executes(ctx -> look(ctx, null, 90.0F)))
                                .then(
                                    Commands.argument("rotation", RotationArgument.rotation())
                                        .executes(ctx -> {
                                            final Vec2 rot = RotationArgument.getRotation(ctx, "rotation")
                                                .getRotation(ctx.getSource());
                                            return look(ctx, rot.y, rot.x);
                                        })
                                )
                        )
                        .then(
                            Commands.literal("spawn")
                                .executes(ctx -> spawn(ctx, null, null, null, null))
                                .then(
                                    Commands.argument("gamemode", GameModeArgument.gameMode())
                                        .executes(ctx -> spawn(ctx, null, null, null,
                                            GameModeArgument.getGameMode(ctx, "gamemode")))
                                )
                                .then(
                                    Commands.literal("in")
                                        .then(
                                            Commands.argument("dimension", DimensionArgument.dimension())
                                                .executes(ctx -> spawn(ctx, null, null,
                                                    DimensionArgument.getDimension(ctx, "dimension"), null))
                                                .then(
                                                    Commands.literal("at")
                                                        .then(
                                                            Commands.argument("position", Vec3Argument.vec3())
                                                                .executes(ctx -> spawn(ctx,
                                                                    Vec3Argument.getVec3(ctx, "position"), null,
                                                                    DimensionArgument.getDimension(ctx, "dimension"), null))
                                                        )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("at")
                                        .then(
                                            Commands.argument("position", Vec3Argument.vec3())
                                                .executes(ctx -> spawn(ctx,
                                                    Vec3Argument.getVec3(ctx, "position"), null, null, null))
                                                .then(
                                                    Commands.argument("gamemode", GameModeArgument.gameMode())
                                                        .executes(ctx -> spawn(ctx,
                                                            Vec3Argument.getVec3(ctx, "position"), null, null,
                                                            GameModeArgument.getGameMode(ctx, "gamemode")))
                                                )
                                                .then(
                                                    Commands.literal("in")
                                                        .then(
                                                            Commands.argument("dimension", DimensionArgument.dimension())
                                                                .executes(ctx -> spawn(ctx,
                                                                    Vec3Argument.getVec3(ctx, "position"), null,
                                                                    DimensionArgument.getDimension(ctx, "dimension"), null))
                                                                .then(
                                                                    Commands.argument("gamemode", GameModeArgument.gameMode())
                                                                        .executes(ctx -> spawn(ctx,
                                                                            Vec3Argument.getVec3(ctx, "position"), null,
                                                                            DimensionArgument.getDimension(ctx, "dimension"),
                                                                            GameModeArgument.getGameMode(ctx, "gamemode")))
                                                                )
                                                                .then(
                                                                    Commands.literal("facing").then(
                                                                        Commands.argument("direction", RotationArgument.rotation())
                                                                            .executes(ctx -> spawn(ctx,
                                                                                Vec3Argument.getVec3(ctx, "position"),
                                                                                RotationArgument.getRotation(ctx, "direction")
                                                                                    .getRotation(ctx.getSource()),
                                                                                DimensionArgument.getDimension(ctx, "dimension"),
                                                                                null))
                                                                            .then(
                                                                                Commands.argument("gamemode", GameModeArgument.gameMode())
                                                                                    .executes(ctx -> spawn(ctx,
                                                                                        Vec3Argument.getVec3(ctx, "position"),
                                                                                        RotationArgument.getRotation(ctx, "direction")
                                                                                            .getRotation(ctx.getSource()),
                                                                                        DimensionArgument.getDimension(ctx, "dimension"),
                                                                                        GameModeArgument.getGameMode(ctx, "gamemode")))
                                                                            )
                                                                    )
                                                                )
                                                        )
                                                )
                                                .then(
                                                    Commands.literal("facing").then(
                                                        Commands.argument("direction", RotationArgument.rotation())
                                                            .executes(ctx -> spawn(ctx,
                                                                Vec3Argument.getVec3(ctx, "position"),
                                                                RotationArgument.getRotation(ctx, "direction")
                                                                    .getRotation(ctx.getSource()),
                                                                null, null))
                                                            .then(
                                                                Commands.argument("gamemode", GameModeArgument.gameMode())
                                                                    .executes(ctx -> spawn(ctx,
                                                                        Vec3Argument.getVec3(ctx, "position"),
                                                                        RotationArgument.getRotation(ctx, "direction")
                                                                            .getRotation(ctx.getSource()),
                                                                        null,
                                                                        GameModeArgument.getGameMode(ctx, "gamemode")))
                                                            )
                                                            .then(
                                                                Commands.literal("in").then(
                                                                    Commands.argument("dimension", DimensionArgument.dimension())
                                                                        .executes(ctx -> spawn(ctx,
                                                                            Vec3Argument.getVec3(ctx, "position"),
                                                                            RotationArgument.getRotation(ctx, "direction")
                                                                                .getRotation(ctx.getSource()),
                                                                            DimensionArgument.getDimension(ctx, "dimension"),
                                                                            null))
                                                                        .then(
                                                                            Commands.argument("gamemode", GameModeArgument.gameMode())
                                                                                .executes(ctx -> spawn(ctx,
                                                                                    Vec3Argument.getVec3(ctx, "position"),
                                                                                    RotationArgument.getRotation(ctx, "direction")
                                                                                        .getRotation(ctx.getSource()),
                                                                                    DimensionArgument.getDimension(ctx, "dimension"),
                                                                                    GameModeArgument.getGameMode(ctx, "gamemode")))
                                                                        )
                                                                )
                                                            )
                                                    )
                                                )
                                        )
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
        final Vec3 pos = posArg != null ? posArg
            : (source.getEntity() != null ? source.getPosition() : Vec3.atCenterOf(source.getLevel().getRespawnData().globalPos().pos()));
        final Vec2 rot = rotArg != null ? rotArg
            : (source.getEntity() != null ? source.getRotation() : Vec2.ZERO);
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
            source.getServer(), name, level, pos, rot.y, rot.x, mode, flying, false,
            // Профиль резолвится в сети, поэтому ошибка создания приходит позже
            // возврата из команды. Отправляем её тем же путём, что и синхронную.
            source::sendFailure);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        return 1;
    }


    /**
     * Узел действия: {@code <действие> [once|continuous|interval <n>|stop]}.
     * Без ритма — {@code once}, как в Carpet.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> action(final String literal,
                                                                    final LabAction act) {
        return Commands.literal(literal)
            .executes(ctx -> run(ctx, act, LabAction.Rhythm.once()))
            .then(Commands.literal("once")
                .executes(ctx -> run(ctx, act, LabAction.Rhythm.once())))
            .then(Commands.literal("continuous")
                .executes(ctx -> run(ctx, act, LabAction.Rhythm.continuous())))
            .then(Commands.literal("stop")
                .executes(ctx -> stopAction(ctx, act)))
            .then(Commands.literal("interval").then(
                Commands.argument("ticks", IntegerArgumentType.integer(1, 72000))
                    .executes(ctx -> run(ctx, act,
                        LabAction.Rhythm.every(IntegerArgumentType.getInteger(ctx, "ticks"))))
            ));
    }

    private static @Nullable LabBot resolve(final CommandContext<CommandSourceStack> ctx) {
        final String name = StringArgumentType.getString(ctx, "name");
        final LabBot bot = LabBotRegistry.get(name);
        if (bot == null) {
            ctx.getSource().sendFailure(Component.literal("no bot " + name));
        }
        return bot;
    }

    private static int run(final CommandContext<CommandSourceStack> ctx,
                           final LabAction act,
                           final LabAction.Rhythm rhythm) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.actions().start(act, rhythm);
        return 1;
    }

    private static int stopAction(final CommandContext<CommandSourceStack> ctx, final LabAction act) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.actions().stop(act);
        return 1;
    }


    /**
     * Режим наблюдателя для бота. Нужен и как инструмент, и как способ проверить сам
     * режим без живого игрока: бота можно поставить рядом с фермой и посмотреть,
     * меняются ли статусы чанков.
     */
    private static int ghost(final CommandContext<CommandSourceStack> ctx) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        final boolean on = io.papermc.paper.lab.ghost.LabGhost.toggle(bot);
        ctx.getSource().sendSuccess(() -> Component.literal(
            bot.labName() + (on ? " ghost on" : " ghost off"))
            .withStyle(on ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static int stop(final CommandContext<CommandSourceStack> ctx) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.actions().stopAll();
        return 1;
    }

    /**
     * Ход. Значение держится, пока его не сменят: это «клавиша зажата», а не шаг.
     *
     * <p>Нужно для двух вещей сразу: завести бота в точку и держать его в стену или
     * в лодке, когда конструкция рассчитана на постоянное давление.
     */
    private static int move(final CommandContext<CommandSourceStack> ctx,
                            final float forward, final float strafing) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.actions().setForward(forward);
        bot.actions().setStrafing(strafing);
        return 1;
    }

    /** Поворот относительно текущего взгляда, в градусах. */
    private static int turn(final CommandContext<CommandSourceStack> ctx,
                            final float yaw, final float pitch) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.snapTo(bot.position(), bot.getYRot() + yaw, bot.getXRot() + pitch);
        bot.setYHeadRot(bot.getYRot());
        return 1;
    }

    /**
     * Сесть в ближайший транспорт.
     *
     * <p>По умолчанию только лодки, вагонетки и лошади — то, что обычно и нужно.
     * {@code mount anything} снимает ограничение.
     *
     * <p>Транспорт по теме исследования: пассажир выпадает из переписи мобкапа до
     * фильтра причины спавна, и лодочные конструкции этим пользуются.
     */
    private static int mount(final CommandContext<CommandSourceStack> ctx, final boolean onlyRideables) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        if (!bot.actions().mount(onlyRideables)) {
            ctx.getSource().sendFailure(Component.literal("nothing to mount nearby"));
            return 0;
        }
        return 1;
    }

    private static int dismount(final CommandContext<CommandSourceStack> ctx) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.actions().dismount();
        return 1;
    }

    /**
     * Поднимать ли бота после смерти.
     *
     * <p>Без этого ночной прогон обрывается на первой смерти: клиента у бота нет,
     * а значит некому прислать запрос на возрождение. Бот поднимается через секунду
     * на том же месте, где его создали.
     */
    private static int respawn(final CommandContext<CommandSourceStack> ctx, final Boolean value) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        final boolean enabled = value == null ? !bot.autoRespawn() : value;
        bot.autoRespawn(enabled);
        ctx.getSource().sendSuccess(() -> Component.literal(
            bot.labName() + (enabled ? " respawn on" : " respawn off"))
            .withStyle(enabled ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static int sneak(final CommandContext<CommandSourceStack> ctx, final boolean value) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.setShiftKeyDown(value);
        return 1;
    }

    private static int sprint(final CommandContext<CommandSourceStack> ctx, final boolean value) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.setSprinting(value);
        return 1;
    }

    private static int hotbar(final CommandContext<CommandSourceStack> ctx, final int slot) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.getInventory().setSelectedSlot(slot - 1);
        return 1;
    }

    private static int look(final CommandContext<CommandSourceStack> ctx,
                            final @Nullable Float yaw,
                            final @Nullable Float pitch) {
        final LabBot bot = resolve(ctx);
        if (bot == null) {
            return 0;
        }
        bot.snapTo(bot.position(),
            yaw == null ? bot.getYRot() : yaw,
            pitch == null ? bot.getXRot() : pitch);
        bot.setYHeadRot(bot.getYRot());
        return 1;
    }

    private static int kill(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final String name = StringArgumentType.getString(ctx, "name");
        if ("all".equalsIgnoreCase(name) || "*".equals(name)) {
            final int removed = LabBotRegistry.removeAll();
            source.sendSuccess(() -> Component.literal("removed: " + removed)
                .withStyle(ChatFormatting.DARK_GRAY), false);
            return removed;
        }
        if (!LabBotRegistry.remove(name)) {
            source.sendFailure(Component.literal("no bot " + name));
            return 0;
        }
        return 1;
    }

    private static int list(final CommandSourceStack source) {
        if (LabBotRegistry.count() == 0) {
            source.sendSuccess(() -> Component.literal("no bots").withStyle(ChatFormatting.DARK_GRAY), false);
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
            if (bot.actions().forward() != 0.0F || bot.actions().strafing() != 0.0F
                || bot.isPassenger() || bot.autoRespawn()) {
                final StringBuilder extra = new StringBuilder("    ");
                if (bot.actions().forward() != 0.0F) {
                    extra.append(bot.actions().forward() > 0 ? "forward " : "back ");
                }
                if (bot.actions().strafing() != 0.0F) {
                    extra.append(bot.actions().strafing() > 0 ? "left " : "right ");
                }
                if (bot.isPassenger()) {
                    extra.append("riding ");
                }
                if (bot.autoRespawn()) {
                    extra.append("respawn ");
                }
                source.sendSuccess(() -> Component.literal(extra.toString().stripTrailing())
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            }

            final var acting = bot.actions().active();
            if (!acting.isEmpty()) {
                final StringBuilder sb = new StringBuilder("    ");
                acting.forEach((act, rhythm) ->
                    sb.append(act.id()).append(" ").append(rhythm.describe()).append("  "));
                source.sendSuccess(() -> Component.literal(sb.toString().stripTrailing())
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            }
        }
        return LabBotRegistry.count();
    }
}
