package io.papermc.paper.lab.log;

import io.papermc.paper.lab.mobcap.MobcapService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TimeUtil;

/**
 * Рисует подписки {@code /log} в футер таб-листа. Модель Carpet {@code HUDController}:
 * обновление раз в секунду, по одной короткой строке на логгер.
 */
public final class LabHud {

    private static final int PERIOD_TICKS = 20;

    private LabHud() {
    }

    /** Вызывается из хука раз в тик; сама решает, пора ли обновлять. */
    public static void tick(final MinecraftServer server) {
        if (server.getTickCount() % PERIOD_TICKS != 0 || !LabLoggers.anySubscribers()) {
            return;
        }
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final List<Component> lines = linesFor(server, player);
            if (lines.isEmpty()) {
                continue;
            }
            final MutableComponent footer = Component.empty();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    footer.append(Component.literal("\n"));
                }
                footer.append(lines.get(i));
            }
            player.connection.send(new ClientboundTabListPacket(Component.empty(), footer));
        }
    }

    /** Сбросить футер игроку — при отписке от последнего логгера. */
    public static void clear(final ServerPlayer player) {
        player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
    }

    private static List<Component> linesFor(final MinecraftServer server, final ServerPlayer player) {
        final String name = player.getScoreboardName();
        final List<Component> lines = new ArrayList<>(4);

        if (LabLoggers.TPS.subscribed(name)) {
            lines.add(tpsLine(server));
        }
        // По одной подписке на цель: мобкапы нескольких игроков и ботов видны одновременно.
        for (final String option : LabLoggers.MOBCAPS.optionsFor(name)) {
            lines.addAll(mobcapLines(server, player, option));
        }
        for (final String option : LabLoggers.COUNTER.optionsFor(name)) {
            lines.addAll(counterLines(player, option));
        }
        for (final String option : LabLoggers.SPAWN.optionsFor(name)) {
            lines.add(spawnLine(player, option));
        }
        return lines;
    }

    private static Component tpsLine(final MinecraftServer server) {
        final double mspt = server.getAverageTickTimeNanos() / (double) TimeUtil.NANOSECONDS_PER_MILLISECOND;
        final ServerTickRateManager trm = server.tickRateManager();
        final double target = trm.isSprinting() ? 0.0D : trm.millisecondsPerTick();
        double tps = 1000.0D / Math.max(target, mspt);
        if (trm.isFrozen()) {
            tps = 0.0D;
        }
        final ChatFormatting color = heat(mspt, trm.millisecondsPerTick());
        return Component.empty()
            .append(Component.literal("TPS ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f", tps)).withStyle(color))
            .append(Component.literal("  MSPT ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.format(Locale.ROOT, "%.1f", mspt)).withStyle(color));
    }

    /**
     * Локальный мобкап Paper.
     *
     * <p>По умолчанию одна строка: ник и кап монстров — то, что нужно для ферм.
     * Опция {@code full} добавляет вторую строку с неудачными попытками спавна.
     *
     * <p>Опция — {@code [ник] [full]}: без ника берётся сам подписчик.
     */
    private static List<Component> mobcapLines(final MinecraftServer server,
                                               final ServerPlayer viewer,
                                               final String option) {
        boolean full = false;
        String targetName = null;
        for (final String token : option.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("full")) {
                full = true;
            } else {
                targetName = token;
            }
        }

        final ServerPlayer target;
        if (targetName == null) {
            target = viewer;
        } else {
            final ServerPlayer byName = server.getPlayerList().getPlayerByName(targetName);
            if (byName == null) {
                return List.of(Component.literal("cap " + targetName + " offline")
                    .withStyle(ChatFormatting.DARK_GRAY));
            }
            target = byName;
        }

        final ServerLevel level = target.level();
        final boolean local = MobcapService.perPlayerEnabled(level);
        final MobcapService.MonsterCap cap = MobcapService.monsterCap(target, level, local);

        final MutableComponent head = Component.empty()
            .append(Component.literal(target.getScoreboardName() + "  ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(Integer.toString(cap.counted())).withStyle(heat(cap.effective(), cap.limit())))
            .append(Component.literal("/" + cap.limit()).withStyle(ChatFormatting.DARK_GRAY));
        if (!local) {
            head.append(Component.literal("  global").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!full) {
            return List.of(head);
        }

        final MutableComponent extra = Component.empty()
            .append(Component.literal("  неудачных попыток ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(Integer.toString(cap.backoff()))
                .withStyle(cap.backoff() > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
        if (cap.backoff() > 0) {
            extra.append(Component.literal(" → занято ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(cap.effective() + "/" + cap.limit())
                    .withStyle(heat(cap.effective(), cap.limit())));
        }
        if (local) {
            final MobcapService.LimitingPlayer limiting = MobcapService.limitingPlayer(
                level, target.chunkPosition(), net.minecraft.world.entity.MobCategory.MONSTER);
            final String limiter = limiting.playerName();
            if (limiter != null && !limiter.equals(target.getScoreboardName())) {
                extra.append(Component.literal("  ограничивает ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(limiter).withStyle(ChatFormatting.RED));
            }
        }
        return List.of(head, extra);
    }

    /**
     * Счётчик воронки. Одна строка на цвет; {@code full} добавляет разбивку по предметам.
     */
    private static List<Component> counterLines(final ServerPlayer viewer, final String option) {
        boolean full = false;
        String colorName = null;
        for (final String token : option.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("full")) {
                full = true;
            } else {
                colorName = token;
            }
        }
        if (colorName == null) {
            return List.of();
        }
        final net.minecraft.world.item.DyeColor color =
            io.papermc.paper.lab.counter.WoolColors.byName(colorName);
        if (color == null) {
            return List.of();
        }

        final ServerLevel level = viewer.level();
        final io.papermc.paper.lab.counter.LabCounter counter =
            io.papermc.paper.lab.counter.LabCounters.existing(level, color);
        if (counter == null || !counter.started()) {
            return List.of(Component.empty()
                .append(Component.literal(color.getName())
                    .withStyle(io.papermc.paper.lab.command.LabCounterCommand.color(color)))
                .append(Component.literal("  —").withStyle(ChatFormatting.DARK_GRAY)));
        }

        final long gameTime = level.getGameTime();
        final List<Component> out = new ArrayList<>();
        out.add(io.papermc.paper.lab.command.LabCounterCommand.summary(counter, gameTime, false));
        if (full) {
            for (final io.papermc.paper.lab.counter.LabCounter.Entry entry : counter.entries()) {
                out.add(Component.empty()
                    .append(Component.literal("  " + entry.count() + " ").withStyle(ChatFormatting.WHITE))
                    .append(entry.name().copy().withStyle(ChatFormatting.DARK_GRAY)));
            }
        }
        return out;
    }

    /**
     * Трасса спавна: где останавливаются попытки.
     *
     * <p>Формат: {@code spawn monster  попыток 812 · позиция 780 · плагин 0 · заспавнено 32 · кап 4410}.
     * Числа накопительные с момента подписки — так видно соотношение причин, а не шум
     * за один тик.
     */
    private static Component spawnLine(final ServerPlayer viewer, final String option) {
        final String categoryName = option == null || option.isBlank() ? "monster" : option.trim();
        net.minecraft.world.entity.MobCategory category = null;
        for (final net.minecraft.world.entity.MobCategory value : net.minecraft.world.entity.MobCategory.values()) {
            if (value.getName().equalsIgnoreCase(categoryName)) {
                category = value;
                break;
            }
        }
        if (category == null) {
            return Component.literal("spawn: нет категории " + categoryName).withStyle(ChatFormatting.DARK_GRAY);
        }

        final long[] counts = io.papermc.paper.lab.spawn.SpawnTrace.snapshot(viewer.level(), category);
        final MutableComponent line = Component.empty()
            .append(Component.literal("spawn ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(category.getName() + "  ").withStyle(ChatFormatting.DARK_GRAY));
        if (counts == null) {
            return line.append(Component.literal("нет попыток").withStyle(ChatFormatting.DARK_GRAY));
        }

        boolean first = true;
        for (final io.papermc.paper.lab.spawn.SpawnTrace.Outcome outcome
            : io.papermc.paper.lab.spawn.SpawnTrace.Outcome.values()) {
            final long value = counts[outcome.ordinal()];
            if (!first) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            first = false;
            line.append(Component.literal(outcome.label() + " ").withStyle(ChatFormatting.DARK_GRAY));
            line.append(Component.literal(Long.toString(value)).withStyle(colourFor(outcome, value)));
        }
        return line;
    }

    private static ChatFormatting colourFor(final io.papermc.paper.lab.spawn.SpawnTrace.Outcome outcome,
                                            final long value) {
        return switch (outcome) {
            case SPAWNED -> value > 0 ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY;
            // Отмена плагином — единственная причина, которой в чистой ванили быть не должно.
            case PLUGIN -> value > 0 ? ChatFormatting.RED : ChatFormatting.DARK_GRAY;
            case CAP_FULL -> value > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY;
            default -> ChatFormatting.WHITE;
        };
    }

    /** Зелёный → жёлтый → красный по заполненности. */
    static ChatFormatting heat(final double value, final double max) {
        if (max <= 0.0D) {
            return ChatFormatting.DARK_GRAY;
        }
        final double ratio = value / max;
        if (ratio >= 1.0D) {
            return ChatFormatting.RED;
        }
        if (ratio >= 0.75D) {
            return ChatFormatting.GOLD;
        }
        if (ratio >= 0.4D) {
            return ChatFormatting.YELLOW;
        }
        return ChatFormatting.GREEN;
    }
}
