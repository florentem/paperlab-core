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
                .requires(source -> source.getBukkitSender().hasPermission("paperlab.player"))
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
            source.getServer(), name, level, pos, rot.y, rot.x, mode, flying,
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
            ctx.getSource().sendFailure(Component.literal("нет бота " + name));
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
