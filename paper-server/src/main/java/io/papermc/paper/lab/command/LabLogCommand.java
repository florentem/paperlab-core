package io.papermc.paper.lab.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.lab.log.LabHud;
import io.papermc.paper.lab.log.LabLogger;
import io.papermc.paper.lab.log.LabLoggers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * {@code /log} — подписки HUD, как в Carpet.
 *
 * <pre>
 * /log                          что есть и на что подписан
 * /log tps                      вкл/выкл
 * /log mobcaps                  свой мобкап
 * /log mobcaps full             свой мобкап + неудачные попытки
 * /log mobcaps &lt;ник&gt;            мобкап игрока или бота, отдельной строкой
 * /log mobcaps &lt;ник&gt; full
 * /log mobcaps clear            снять подписки этого логгера
 * /log clear                    снять всё
 * </pre>
 *
 * Повторная команда с той же целью выключает её; с другим флагом — заменяет.
 */
public final class LabLogCommand {

    private LabLogCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("log")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.literal("clear").executes(ctx -> clearAll(ctx.getSource())))
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(LabLoggers.names(), builder))
                        .executes(ctx -> toggle(ctx, null))
                        .then(
                            Commands.argument("target", StringArgumentType.word())
                                .suggests(LabLogCommand::suggestOptions)
                                .executes(ctx -> toggle(ctx, StringArgumentType.getString(ctx, "target")))
                                .then(
                                    Commands.argument("flag", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                            SharedSuggestionProvider.suggest(new String[]{"full"}, builder))
                                        .executes(ctx -> toggle(ctx,
                                            StringArgumentType.getString(ctx, "target")
                                                + " " + StringArgumentType.getString(ctx, "flag")))
                                )
                        )
                )
        );
    }

    private static CompletableFuture<Suggestions> suggestOptions(final CommandContext<CommandSourceStack> ctx,
                                                                final SuggestionsBuilder builder) {
        final LabLogger logger = LabLoggers.get(StringArgumentType.getString(ctx, "name"));
        final List<String> opts = new ArrayList<>();
        if (logger != null) {
            if (logger.freeform()) {
                opts.add("full");
                ctx.getSource().getServer().getPlayerList().getPlayers()
                    .forEach(p -> opts.add(p.getScoreboardName()));
            } else {
                opts.addAll(logger.options());
            }
        }
        opts.add("clear");
        return SharedSuggestionProvider.suggest(opts, builder);
    }

    private static int list(final CommandSourceStack source) {
        final ServerPlayer player = (ServerPlayer) source.getEntity();
        final String name = player.getScoreboardName();
        final MutableComponent line = Component.empty();
        boolean first = true;
        for (final LabLogger logger : LabLoggers.all()) {
            if (!first) {
                line.append(Component.literal("  "));
            }
            first = false;
            final var options = logger.optionsFor(name);
            if (options.isEmpty()) {
                line.append(Component.literal(logger.name()).withStyle(ChatFormatting.DARK_GRAY));
                continue;
            }
            boolean firstOption = true;
            for (final String option : options) {
                if (!firstOption) {
                    line.append(Component.literal(" "));
                }
                firstOption = false;
                line.append(Component.literal(label(logger, option)).withStyle(ChatFormatting.GREEN));
            }
        }
        source.sendSuccess(() -> line, false);
        return 1;
    }

    private static int toggle(final CommandContext<CommandSourceStack> ctx, final @Nullable String option) {
        final CommandSourceStack source = ctx.getSource();
        final ServerPlayer player = (ServerPlayer) source.getEntity();
        final String playerName = player.getScoreboardName();
        final String loggerName = StringArgumentType.getString(ctx, "name");

        final LabLogger logger = LabLoggers.get(loggerName);
        if (logger == null) {
            source.sendFailure(Component.literal("нет логгера " + loggerName));
            return 0;
        }

        if (option != null && option.equalsIgnoreCase("clear")) {
            logger.unsubscribeAll(playerName);
            if (logger == LabLoggers.SPAWN) {
                io.papermc.paper.lab.spawn.SpawnTrace.setEnabled(LabLoggers.SPAWN.hasSubscribers());
            }
            refresh(player);
            source.sendSuccess(() -> Component.literal(logger.name() + " off")
                .withStyle(ChatFormatting.DARK_GRAY), false);
            return 1;
        }

        if (option != null && !logger.freeform()
            && !logger.options().isEmpty() && !logger.options().contains(option)) {
            source.sendFailure(Component.literal(
                "опции " + logger.name() + ": " + String.join(", ", logger.options())));
            return 0;
        }

        final boolean on = logger.toggle(playerName, option);
        // Сбор трассы спавна стоит в горячем пути, поэтому включаем его только
        // пока на логгер кто-то подписан.
        if (logger == LabLoggers.SPAWN) {
            io.papermc.paper.lab.spawn.SpawnTrace.setEnabled(LabLoggers.SPAWN.hasSubscribers());
        }
        refresh(player);
        final String text = label(logger, option == null ? "" : option);
        source.sendSuccess(() -> Component.literal(text + (on ? " on" : " off"))
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static int clearAll(final CommandSourceStack source) {
        final ServerPlayer player = (ServerPlayer) source.getEntity();
        LabLoggers.unsubscribeAll(player.getScoreboardName());
        io.papermc.paper.lab.spawn.SpawnTrace.setEnabled(LabLoggers.SPAWN.hasSubscribers());
        LabHud.clear(player);
        source.sendSuccess(() -> Component.literal("off").withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static String label(final LabLogger logger, final String option) {
        return option.isEmpty() ? logger.name() : logger.name() + ":" + option.replace(' ', '/');
    }

    /** Если подписок больше нет — убрать футер сразу, не ждать следующего обновления. */
    private static void refresh(final ServerPlayer player) {
        final String name = player.getScoreboardName();
        for (final LabLogger logger : LabLoggers.all()) {
            if (logger.subscribed(name)) {
                return;
            }
        }
        LabHud.clear(player);
    }
}
