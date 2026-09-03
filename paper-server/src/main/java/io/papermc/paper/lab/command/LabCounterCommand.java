package io.papermc.paper.lab.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.lab.counter.LabCounter;
import io.papermc.paper.lab.counter.LabCounters;
import io.papermc.paper.lab.counter.WoolColors;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;

/**
 * {@code /counter} — счётчики воронок.
 *
 * <pre>
 * /counter                    все активные счётчики
 * /counter &lt;цвет&gt;             разбивка по предметам
 * /counter &lt;цвет&gt; reset
 * /counter reset              сбросить все
 * </pre>
 *
 * Постоянное наблюдение — через {@code /log counter &lt;цвет&gt;}.
 */
public final class LabCounterCommand {

    private LabCounterCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("counter")
                .executes(ctx -> listAll(ctx.getSource()))
                .then(Commands.literal("reset").executes(ctx -> resetAll(ctx.getSource())))
                .then(
                    Commands.argument("color", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                            java.util.Arrays.stream(DyeColor.values()).map(DyeColor::getName).toList(), builder))
                        .executes(ctx -> show(ctx))
                        .then(Commands.literal("reset").executes(ctx -> reset(ctx)))
                )
        );
    }

    private static int listAll(final CommandSourceStack source) {
        final List<LabCounter> counters = LabCounters.active();
        if (counters.isEmpty()) {
            source.sendSuccess(() -> Component.literal("счётчиков нет — направь воронку в шерсть")
                .withStyle(ChatFormatting.DARK_GRAY), false);
            return 0;
        }
        final long gameTime = source.getLevel().getGameTime();
        for (final LabCounter counter : counters) {
            source.sendSuccess(() -> summary(counter, gameTime, true), false);
        }
        return counters.size();
    }

    private static int show(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final DyeColor color = WoolColors.byName(StringArgumentType.getString(ctx, "color"));
        if (color == null) {
            source.sendFailure(Component.literal("нет такого цвета"));
            return 0;
        }
        final Level level = source.getLevel();
        final LabCounter counter = LabCounters.existing(level, color);
        if (counter == null || !counter.started()) {
            source.sendSuccess(() -> Component.literal(color.getName() + " пусто")
                .withStyle(ChatFormatting.DARK_GRAY), false);
            return 0;
        }
        final long gameTime = level.getGameTime();
        source.sendSuccess(() -> summary(counter, gameTime, false), false);
        for (final LabCounter.Entry entry : counter.entries()) {
            source.sendSuccess(() -> Component.empty()
                .append(Component.literal("  " + entry.count() + "  ").withStyle(ChatFormatting.WHITE))
                .append(entry.name().copy().withStyle(ChatFormatting.GRAY)), false);
        }
        return 1;
    }

    private static int reset(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final DyeColor color = WoolColors.byName(StringArgumentType.getString(ctx, "color"));
        if (color == null) {
            source.sendFailure(Component.literal("нет такого цвета"));
            return 0;
        }
        LabCounters.of(source.getLevel(), color).reset(source.getLevel().getGameTime());
        source.sendSuccess(() -> Component.literal(color.getName() + " reset")
            .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static int resetAll(final CommandSourceStack source) {
        final int count = LabCounters.resetAll(source.getLevel().getGameTime());
        source.sendSuccess(() -> Component.literal("reset: " + count)
            .withStyle(ChatFormatting.DARK_GRAY), false);
        return count;
    }

    /** Одна строка: цвет, всего, рейт, время. Измерение — только когда смотрим список целиком. */
    public static MutableComponent summary(final LabCounter counter, final long gameTime,
                                           final boolean withDimension) {
        final Double perHour = counter.perHour(gameTime);
        final double minutes = counter.elapsedTicks(gameTime) / 1200.0D;
        final MutableComponent line = Component.empty()
            .append(Component.literal(counter.color().getName()).withStyle(color(counter.color())));
        if (withDimension) {
            line.append(Component.literal("@" + counter.dimension()).withStyle(ChatFormatting.DARK_GRAY));
        }
        line.append(Component.literal("  " + counter.total()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  " + (perHour == null
                    ? "N/A"
                    : String.format(Locale.ROOT, "%.0f/h", perHour)))
                .withStyle(ChatFormatting.AQUA))
            .append(Component.literal(String.format(Locale.ROOT, "  %.1f мин", minutes))
                .withStyle(ChatFormatting.DARK_GRAY));
        return line;
    }

    /** Ближайший цвет чата к цвету шерсти. */
    public static ChatFormatting color(final DyeColor dye) {
        return switch (dye) {
            case WHITE -> ChatFormatting.WHITE;
            case ORANGE -> ChatFormatting.GOLD;
            case MAGENTA -> ChatFormatting.LIGHT_PURPLE;
            case LIGHT_BLUE -> ChatFormatting.AQUA;
            case YELLOW -> ChatFormatting.YELLOW;
            case LIME -> ChatFormatting.GREEN;
            case PINK -> ChatFormatting.LIGHT_PURPLE;
            case GRAY -> ChatFormatting.DARK_GRAY;
            case LIGHT_GRAY -> ChatFormatting.GRAY;
            case CYAN -> ChatFormatting.DARK_AQUA;
            case PURPLE -> ChatFormatting.DARK_PURPLE;
            case BLUE -> ChatFormatting.BLUE;
            case BROWN -> ChatFormatting.DARK_RED;
            case GREEN -> ChatFormatting.DARK_GREEN;
            case RED -> ChatFormatting.RED;
            case BLACK -> ChatFormatting.DARK_GRAY;
        };
    }
}
