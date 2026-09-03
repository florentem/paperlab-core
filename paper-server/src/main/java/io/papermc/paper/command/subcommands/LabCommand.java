package io.papermc.paper.command.subcommands;

import io.papermc.paper.command.PaperSubcommand;
import io.papermc.paper.lab.Lab;
import io.papermc.paper.lab.LabMode;
import io.papermc.paper.lab.activation.EarSnapshot;
import io.papermc.paper.lab.chunk.ChunkStatusProbe;
import io.papermc.paper.lab.mobcap.MobcapService;
import io.papermc.paper.lab.mobcap.MobcapSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;

/**
 * Диагностические команды Technical Lab.
 *
 * <p>Пока spike-уровень: показывает то, что читается из движка без побочных эффектов.
 * Полноценный UX {@code /log} с подписками и интервалами — отдельный модуль.
 *
 * <pre>
 * /paper lab status
 * /paper lab mode &lt;OFF|OBSERVE|CONTROL|REPLAY&gt;
 * /paper lab mobcaps [игрок]
 * /paper lab ear
 * </pre>
 */
@DefaultQualifier(NonNull.class)
public final class LabCommand implements PaperSubcommand {

    @Override
    public boolean execute(final CommandSender sender, final String subCommand, final String[] args) {
        final String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        final String[] rest = args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);

        switch (action) {
            case "status" -> this.status(sender);
            case "mode" -> this.mode(sender, rest);
            case "mobcaps" -> this.mobcaps(sender, rest);
            case "ear" -> this.ear(sender);
            case "chunk", "chunks" -> this.chunk(sender, rest);
            default -> sender.sendMessage(Component.text(
                "Неизвестное действие. Доступно: status, mode, mobcaps, ear, chunk", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String subCommand, final String[] args) {
        if (args.length <= 1) {
            return List.of("status", "mode", "mobcaps", "ear", "chunk");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            return Arrays.stream(LabMode.values()).map(Enum::name).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mobcaps")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).map(s -> (String) s).toList();
        }
        return List.of();
    }

    private void status(final CommandSender sender) {
        sender.sendMessage(Component.text("Paper Technical Lab", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  режим: " + Lab.mode().name(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text(
            "  сбор данных: " + (Lab.collecting() ? "включён" : "выключен"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
            "  вмешательство разрешено: " + (Lab.mode().mayIntervene() ? "да" : "нет"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  режим не гарантирует нулевых накладных расходов "
            + "— это измеряемая величина", NamedTextColor.DARK_GRAY));

        for (final org.bukkit.World world : Bukkit.getWorlds()) {
            final ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
            sender.sendMessage(Component.text("  " + world.getName()
                + ": per-player-mob-spawns=" + MobcapService.perPlayerEnabled(level)
                + ", count-all-mobs-for-spawning=" + MobcapService.countAllMobs(level)
                + ", simulation-distance=" + world.getSimulationDistance(),
                NamedTextColor.AQUA));
        }
    }

    private void mode(final CommandSender sender, final String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Текущий режим: " + Lab.mode().name(), NamedTextColor.WHITE));
            return;
        }
        final LabMode requested = LabMode.parse(args[0]);
        if (requested == LabMode.OFF && !args[0].equalsIgnoreCase("off")) {
            sender.sendMessage(Component.text(
                "Неизвестный режим '" + args[0] + "'. Доступно: OFF, OBSERVE, CONTROL, REPLAY",
                NamedTextColor.RED));
            return;
        }
        final LabMode previous = Lab.mode();
        Lab.setMode(requested);
        sender.sendMessage(Component.text(
            "Режим: " + previous.name() + " -> " + requested.name(), NamedTextColor.GREEN));
        sender.sendMessage(Component.text(
            "Накопленные снимки инвалидированы: числа из разных режимов несопоставимы",
            NamedTextColor.GRAY));
    }

    private void mobcaps(final CommandSender sender, final String[] args) {
        final Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text(
                    "Из консоли нужно указать игрока: /paper lab mobcaps <игрок>", NamedTextColor.RED));
                return;
            }
            target = self;
        } else {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок не найден: " + args[0], NamedTextColor.RED));
                return;
            }
        }

        final ServerPlayer serverPlayer = ((CraftPlayer) target).getHandle();
        final ServerLevel level = serverPlayer.level();
        final ChunkPos playerChunk = serverPlayer.chunkPosition();

        sender.sendMessage(Component.text("Локальные мобкапы: " + target.getName(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  чанк игрока " + playerChunk.x() + ", " + playerChunk.z()
            + " | область учёта = simulation distance (" + level.getWorld().getSimulationDistance() + " чанков)",
            NamedTextColor.DARK_GRAY));

        final List<MobcapSnapshot> snapshots = MobcapService.snapshot(serverPlayer);
        for (final MobcapSnapshot snapshot : snapshots) {
            if (snapshot.status() == MobcapSnapshot.Status.NOT_SPAWN_LIMITED) {
                continue;
            }
            final MobcapService.LimitingPlayer limiting =
                MobcapService.limitingPlayer(level, playerChunk, snapshot.category());
            final boolean self = limiting.playerName() != null
                && limiting.playerName().equals(target.getName());
            sender.sendMessage(Component.text(
                snapshot.describe(self ? null : limiting.playerName(), limiting.playersInRange()),
                snapshot.valid() ? NamedTextColor.WHITE : NamedTextColor.GRAY));
            if (snapshot.valid()) {
                sender.sendMessage(Component.text(
                    "  бюджет чанка игрока по движку: maxSpawns=" + limiting.maxSpawns()
                        + (limiting.canSpawn() ? "" : " — спавн в этом чанке невозможен"),
                    NamedTextColor.DARK_AQUA));
            }
        }
    }

    private void ear(final CommandSender sender) {
        if (!(sender instanceof Player self)) {
            sender.sendMessage(Component.text("Команда доступна только игроку", NamedTextColor.RED));
            return;
        }
        final ServerPlayer serverPlayer = ((CraftPlayer) self).getHandle();
        final ServerLevel level = serverPlayer.level();

        // Ближайшие сущности в небольшом радиусе. Только уже загруженные: getEntities
        // не грузит чанки и не добавляет tickets.
        final List<Entity> nearby = new ArrayList<>(
            level.getEntities(serverPlayer, serverPlayer.getBoundingBox().inflate(16.0D)));
        if (nearby.isEmpty()) {
            sender.sendMessage(Component.text("Рядом нет загруженных сущностей", NamedTextColor.GRAY));
            return;
        }

        sender.sendMessage(Component.text("EAR: сущности в радиусе 16 блоков", NamedTextColor.GOLD));
        int shown = 0;
        for (final Entity entity : nearby) {
            if (shown >= 10) {
                sender.sendMessage(Component.text(
                    "  ... ещё " + (nearby.size() - shown) + " (вывод ограничен)", NamedTextColor.DARK_GRAY));
                break;
            }
            final boolean entityTicking = ChunkStatusProbe.entityTicking(level, entity.chunkPosition());
            sender.sendMessage(Component.text(
                EarSnapshot.of(entity, entityTicking).describe(), NamedTextColor.WHITE));
            shown++;
        }
    }

    private void chunk(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player self)) {
            sender.sendMessage(Component.text("Команда доступна только игроку", NamedTextColor.RED));
            return;
        }
        final ServerPlayer serverPlayer = ((CraftPlayer) self).getHandle();
        final ServerLevel level = serverPlayer.level();
        final ChunkPos playerChunk = serverPlayer.chunkPosition();

        final int radius = args.length > 0 ? clamp(parseIntOr(args[0], 8), 1, 12) : 8;

        sender.sendMessage(Component.text("Статусы чанков вокруг игрока, radius " + radius
            + " | simulation-distance=" + level.getWorld().getSimulationDistance(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  ENTITY_TICKING = E, BLOCK_TICKING = B, FULL = F, "
            + "не загружен = '.'", NamedTextColor.DARK_GRAY));

        for (int dz = -radius; dz <= radius; dz++) {
            final StringBuilder row = new StringBuilder(2 * radius + 3);
            for (int dx = -radius; dx <= radius; dx++) {
                final net.minecraft.server.level.FullChunkStatus status =
                    ChunkStatusProbe.statusOf(level, playerChunk.x() + dx, playerChunk.z() + dz);
                if (dx == 0 && dz == 0) {
                    row.append('@');
                    continue;
                }
                row.append(status == null ? '.' : switch (status) {
                    case ENTITY_TICKING -> 'E';
                    case BLOCK_TICKING -> 'B';
                    case FULL -> 'F';
                    case INACCESSIBLE -> 'i';
                });
            }
            sender.sendMessage(Component.text("  " + row, NamedTextColor.WHITE));
        }

        sender.sendMessage(Component.text(
            ChunkStatusProbe.describe(level, playerChunk, playerChunk), NamedTextColor.AQUA));
    }

    private static int parseIntOr(final String raw, final int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (final NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
