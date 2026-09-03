package io.papermc.paper.lab.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.lab.chunkmap.ChunkMapProtocol;
import io.papermc.paper.lab.chunkmap.ChunkMapTracker;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * {@code /labchunks <игрок>} — сводка статусов чанков вокруг игрока.
 *
 * <p>Это не карта: карту рисует клиентский ChunkDebug. Здесь только числа, и нужны они
 * для проверки из консоли — например, чтобы убедиться, что наблюдатель не создаёт вокруг
 * себя ticking-чанков.
 */
public final class LabChunksCommand {

    private LabChunksCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("labchunks")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("spawn")
                        .then(Commands.literal("on").executes(ctx -> trace(ctx.getSource(), true, false)))
                        .then(Commands.literal("off").executes(ctx -> trace(ctx.getSource(), false, false)))
                        .then(Commands.literal("reset").executes(ctx -> trace(ctx.getSource(), true, true)))
                        .executes(ctx -> showTrace(ctx.getSource()))
                )
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                            ctx.getSource().getServer().getPlayerList().getPlayers().stream()
                                .map(ServerPlayer::getScoreboardName).toList(), builder))
                        .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "player")))
                )
        );
    }


    /**
     * Управление трассой спавна из консоли: включить, выключить, сбросить.
     * В игре то же самое живёт как подписка {@code /log spawn}.
     */
    private static int trace(final CommandSourceStack source, final boolean on, final boolean reset) {
        if (reset) {
            io.papermc.paper.lab.spawn.SpawnTrace.reset();
        }
        io.papermc.paper.lab.spawn.SpawnTrace.setEnabled(on);
        source.sendSuccess(() -> Component.literal("spawn trace " + (on ? "on" : "off")
            + (reset ? " (сброшено)" : "")).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Числа трассы по всем мирам и категориям, у которых есть попытки. */
    private static int showTrace(final CommandSourceStack source) {
        if (!io.papermc.paper.lab.spawn.SpawnTrace.enabled()) {
            source.sendSuccess(() -> Component.literal("spawn trace выключена: /labchunks spawn on")
                .withStyle(ChatFormatting.DARK_GRAY), false);
            return 0;
        }
        int shown = 0;
        for (final ServerLevel level : source.getServer().getAllLevels()) {
            for (final net.minecraft.world.entity.MobCategory category
                : net.minecraft.world.entity.MobCategory.values()) {
                final long[] counts = io.papermc.paper.lab.spawn.SpawnTrace.snapshot(level, category);
                if (counts == null) {
                    continue;
                }
                final StringBuilder sb = new StringBuilder();
                sb.append(level.dimension().identifier().getPath())
                    .append("  ").append(category.getName()).append("  ");
                for (final io.papermc.paper.lab.spawn.SpawnTrace.Outcome outcome
                    : io.papermc.paper.lab.spawn.SpawnTrace.Outcome.values()) {
                    sb.append(outcome.label()).append(' ')
                        .append(counts[outcome.ordinal()]).append("  ");
                }
                source.sendSuccess(() -> Component.literal(sb.toString().stripTrailing())
                    .withStyle(ChatFormatting.WHITE), false);
                shown++;
            }
        }
        if (shown == 0) {
            source.sendSuccess(() -> Component.literal("попыток пока не было")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return shown;
    }

    private static int run(final CommandSourceStack source, final String name) {
        final ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
        if (player == null) {
            source.sendFailure(Component.literal("нет игрока " + name));
            return 0;
        }
        final ServerLevel level = player.level();
        final ChunkPos centre = player.chunkPosition();
        final List<ChunkMapProtocol.ChunkInfo> chunks = ChunkMapTracker.snapshot(level, false);

        final Map<FullChunkStatus, Integer> total = new EnumMap<>(FullChunkStatus.class);
        final Map<FullChunkStatus, Integer> near = new EnumMap<>(FullChunkStatus.class);
        int maxTickingDistance = -1;

        for (final ChunkMapProtocol.ChunkInfo info : chunks) {
            final FullChunkStatus status = ChunkLevel.fullStatus(info.statusLevel());
            total.merge(status, 1, Integer::sum);

            final int distance = Math.max(
                Math.abs(info.position().x() - centre.x()),
                Math.abs(info.position().z() - centre.z()));
            if (distance <= 12) {
                near.merge(status, 1, Integer::sum);
            }
            if (status.isOrAfter(FullChunkStatus.ENTITY_TICKING) && distance > maxTickingDistance) {
                maxTickingDistance = distance;
            }
        }

        final int simDistance = ca.spottedleaf.moonrise.common.PlatformHooks.get().getTickViewDistance(player);
        final int sendDistance = ca.spottedleaf.moonrise.common.PlatformHooks.get().getSendViewDistance(player);
        final boolean ghost = io.papermc.paper.lab.ghost.LabGhost.isGhost(player);
        source.sendSuccess(() -> Component.literal(
            name + " @ " + centre.x() + "," + centre.z() + "  мир " + level.getWorld().getName()
                + "  sim=" + simDistance + " send=" + sendDistance + (ghost ? "  GHOST" : ""))
            .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("  всего: " + describe(total))
            .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("  в радиусе 12: " + describe(near))
            .withStyle(ChatFormatting.WHITE), false);
        final int furthest = maxTickingDistance;
        source.sendSuccess(() -> Component.literal(
            "  самый дальний ENTITY_TICKING чанк мира: d=" + furthest)
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static String describe(final Map<FullChunkStatus, Integer> counts) {
        if (counts.isEmpty()) {
            return "нет";
        }
        final StringBuilder sb = new StringBuilder();
        counts.forEach((status, count) -> sb.append(status.name()).append(' ').append(count).append("  "));
        return sb.toString().stripTrailing();
    }
}
