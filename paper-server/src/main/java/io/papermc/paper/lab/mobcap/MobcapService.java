package io.papermc.paper.lab.mobcap;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import io.papermc.paper.lab.Lab;
import io.papermc.paper.lab.SpawnPhase;
import io.papermc.paper.lab.SpawnPhaseTracker;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.craftbukkit.util.CraftSpawnCategory;
import org.bukkit.entity.SpawnCategory;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Чтение локального мобкапа Paper без изменения состояния.
 *
 * <p>Ничего не пересчитывает: значения берутся из {@code ServerPlayer.mobCounts} и
 * {@code ServerPlayer.mobBackoffCounts}, которые движок обновляет в начале
 * {@code ServerChunkCache.tickChunks}. Лимит определяется ровно тем же способом, что и в
 * {@code NaturalSpawner.spawnForChunk}, чтобы инструмент не показывал другое число, чем
 * использует спавнер.
 *
 * <p>Не добавляет chunk tickets, не грузит чанки, не вызывает RNG и не создаёт сущностей.
 * Все запросы должны выполняться на главном потоке сервера.
 */
public final class MobcapService {

    private MobcapService() {
    }

    /**
     * Категории, по которым имеет смысл показывать локальный кап. Порядок как в
     * {@code NaturalSpawner.SPAWNING_CATEGORIES} не требуется — это только вывод.
     */
    public static List<MobcapSnapshot> snapshot(final ServerPlayer player) {
        final ServerLevel level = player.level();
        final List<MobcapSnapshot> out = new ArrayList<>(MobCategory.values().length);
        for (final MobCategory category : MobCategory.values()) {
            out.add(snapshot(player, category));
        }
        return out;
    }

    public static MobcapSnapshot snapshot(final ServerPlayer player, final MobCategory category) {
        final ServerLevel level = player.level();
        final int index = category.ordinal();

        final boolean perPlayer = level.paperConfig().entities.spawning.perPlayerMobSpawns;

        // Лимит определяется ровно как в NaturalSpawner.spawnForChunk.
        int limit = category.getMaxInstancesPerChunk();
        MobcapSnapshot.LimitSource limitSource = MobcapSnapshot.LimitSource.MOB_CATEGORY_DEFAULT;
        final SpawnCategory spawnCategory = CraftSpawnCategory.toBukkit(category);
        if (CraftSpawnCategory.isValidForLimits(spawnCategory)) {
            limit = level.getWorld().getSpawnLimit(spawnCategory);
            limitSource = MobcapSnapshot.LimitSource.BUKKIT_SPAWN_LIMITS;
        }

        final MobcapSnapshot.Status status;
        if (!perPlayer) {
            // Массивы движком не обновляются вообще — показывать их значения нельзя.
            status = MobcapSnapshot.Status.PER_PLAYER_DISABLED;
        } else if (!CraftSpawnCategory.isValidForLimits(spawnCategory)) {
            status = MobcapSnapshot.Status.NOT_SPAWN_LIMITED;
        } else if (limit == 0) {
            status = MobcapSnapshot.Status.CATEGORY_DISABLED;
        } else {
            status = MobcapSnapshot.Status.OK;
        }

        final boolean readable = status == MobcapSnapshot.Status.OK
            || status == MobcapSnapshot.Status.CATEGORY_DISABLED
            || status == MobcapSnapshot.Status.NOT_SPAWN_LIMITED;

        final int counted = readable ? player.mobCounts[index] : 0;
        final int backoff = readable ? player.mobBackoffCounts[index] : 0;

        final SpawnPhase phase = SpawnPhaseTracker.phaseOf(level);
        final long age = SpawnPhaseTracker.ageInTicks(level);

        return new MobcapSnapshot(
            level.getWorld().getName(),
            player.getScoreboardName(),
            category,
            status,
            counted,
            backoff,
            status == MobcapSnapshot.Status.PER_PLAYER_DISABLED ? -1 : limit,
            status == MobcapSnapshot.Status.PER_PLAYER_DISABLED
                ? MobcapSnapshot.LimitSource.NONE
                : limitSource,
            level.getGameTime(),
            phase,
            age
        );
    }

    /**
     * Кто именно ограничивает бюджет конкретного чанка.
     *
     * <p>Движок берёт <b>минимальный</b> остаток среди игроков, у которых этот чанк попадает
     * в {@code NearbyMapType.TICK_VIEW_DISTANCE} (то есть в simulation distance). Второй игрок
     * рядом бюджет не увеличивает — он может только его урезать. Именно этого не показывает
     * штатный {@code /paper playermobcaps}.
     *
     * @return результат с ограничивающим игроком, либо пустой результат, если рядом никого нет
     */
    public static LimitingPlayer limitingPlayer(final ServerLevel level,
                                                final ChunkPos chunkPos,
                                                final MobCategory category) {
        int limit = category.getMaxInstancesPerChunk();
        final SpawnCategory spawnCategory = CraftSpawnCategory.toBukkit(category);
        if (CraftSpawnCategory.isValidForLimits(spawnCategory)) {
            limit = level.getWorld().getSpawnLimit(spawnCategory);
        }

        final NearbyPlayers nearbyPlayers = level.moonrise$getNearbyPlayers();
        final ReferenceList<ServerPlayer> inRange =
            nearbyPlayers.getPlayers(chunkPos, NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE);
        if (inRange == null || inRange.size() == 0) {
            // Движок в этом случае получает maxSpawns = 0: спавн невозможен.
            return new LimitingPlayer(null, 0, 0, limit);
        }

        final ServerPlayer[] raw = inRange.getRawDataUnchecked();
        final int len = inRange.size();

        int minDiff = Integer.MAX_VALUE;
        ServerPlayer worst = null;
        for (int i = 0; i < len; i++) {
            final ServerPlayer candidate = raw[i];
            final int diff = limit - level.getChunkSource().chunkMap.getMobCountNear(candidate, category);
            if (diff < minDiff) {
                minDiff = diff;
                worst = candidate;
            }
        }

        final int maxSpawns = minDiff == Integer.MAX_VALUE ? 0 : minDiff;
        return new LimitingPlayer(
            worst == null ? null : worst.getScoreboardName(),
            len,
            maxSpawns,
            limit
        );
    }

    /**
     * @param playerName     ограничивающий игрок; {@code null}, если в области нет игроков
     * @param playersInRange сколько игроков держат этот чанк в simulation distance
     * @param maxSpawns      бюджет чанка, как его вычисляет движок; {@code <= 0} — спавна нет
     * @param limit          действующий лимит категории
     */
    public record LimitingPlayer(@Nullable String playerName, int playersInRange, int maxSpawns, int limit) {

        public boolean canSpawn() {
            return this.maxSpawns > 0;
        }
    }

    /**
     * Диагностический мировой снимок. Нужен, когда локальная схема выключена, и как
     * контрольное значение. При включённом per-player эта величина <b>не является</b>
     * действующим лимитом ни для одного игрока: глобальная проверка категории обходится
     * в {@code getFilteredSpawningCategories}.
     */
    public static boolean perPlayerEnabled(final ServerLevel level) {
        return level.paperConfig().entities.spawning.perPlayerMobSpawns;
    }

    /**
     * Учитываются ли в natural cap мобы с причинами, отличными от NATURAL/CHUNK_GEN.
     * При Paper-дефолте {@code false} мобы от зелий, спавнеров и порталов в кап не попадают.
     */
    public static boolean countAllMobs(final ServerLevel level) {
        return level.paperConfig().entities.spawning.countAllMobsForSpawning;
    }


    /**
     * Короткий код категории для HUD.
     */
    public static String shortCode(final MobCategory category) {
        return switch (category) {
            case MONSTER -> "M";
            case CREATURE -> "A";
            case AMBIENT -> "Am";
            case AXOLOTLS -> "Ax";
            case UNDERGROUND_WATER_CREATURE -> "Uw";
            case WATER_CREATURE -> "W";
            case WATER_AMBIENT -> "Wa";
            case MISC -> "Ms";
        };
    }

    /**
     * Одна запись HUD-строки мобкапа.
     *
     * @param code  короткий код категории
     * @param value отображаемое число: {@code "70"} либо {@code "70+2"}, если backoff не ноль
     * @param used  эффективная занятость для раскраски
     * @param limit действующий лимит
     */
    public record HudEntry(String code, String value, int used, int limit) {
    }

    /**
     * Записи для HUD. Порядок фиксирован, категории без лимита пропускаются.
     *
     * @param local {@code true} — локальный кап игрока; {@code false} — мировой снимок движка
     */
    public static List<HudEntry> hudEntries(final ServerPlayer player,
                                            final ServerLevel level,
                                            final boolean local) {
        final List<HudEntry> out = new ArrayList<>(HUD_ORDER.length);
        for (final MobCategory category : HUD_ORDER) {
            final SpawnCategory spawnCategory = CraftSpawnCategory.toBukkit(category);
            if (!CraftSpawnCategory.isValidForLimits(spawnCategory)) {
                continue;
            }
            final int limit = level.getWorld().getSpawnLimit(spawnCategory);
            if (limit <= 0) {
                continue;
            }
            final int counted;
            final int backoff;
            if (local) {
                final int index = category.ordinal();
                counted = player.mobCounts[index];
                backoff = player.mobBackoffCounts[index];
            } else {
                final net.minecraft.world.level.NaturalSpawner.SpawnState state =
                    level.getChunkSource().getLastSpawnState();
                counted = state == null ? -1 : state.getMobCategoryCounts().getOrDefault(category, -1);
                backoff = 0;
            }
            if (counted < 0) {
                continue;
            }
            final String value = backoff > 0 ? (counted + "+" + backoff) : Integer.toString(counted);
            out.add(new HudEntry(shortCode(category), value, counted + backoff, limit));
        }
        return out;
    }

    private static final MobCategory[] HUD_ORDER = {
        MobCategory.MONSTER,
        MobCategory.CREATURE,
        MobCategory.AMBIENT,
        MobCategory.WATER_CREATURE,
        MobCategory.WATER_AMBIENT,
        MobCategory.UNDERGROUND_WATER_CREATURE,
        MobCategory.AXOLOTLS,
    };


    /**
     * Кап монстров — единственная категория, которая нужна для ферм.
     *
     * @param counted   учтённые движком мобы
     * @param backoff   штраф за неудачные попытки спавна; это не живые мобы
     * @param limit     действующий лимит из bukkit.yml
     */
    public record MonsterCap(int counted, int backoff, int limit) {

        /** Ровно то, что движок вычитает из лимита: {@code getMobCountNear}. */
        public int effective() {
            return this.counted + this.backoff;
        }
    }

    public static MonsterCap monsterCap(final ServerPlayer player,
                                        final ServerLevel level,
                                        final boolean local) {
        final MobCategory category = MobCategory.MONSTER;
        final SpawnCategory spawnCategory = CraftSpawnCategory.toBukkit(category);
        final int limit = CraftSpawnCategory.isValidForLimits(spawnCategory)
            ? level.getWorld().getSpawnLimit(spawnCategory)
            : category.getMaxInstancesPerChunk();

        if (local) {
            final int index = category.ordinal();
            return new MonsterCap(player.mobCounts[index], player.mobBackoffCounts[index], limit);
        }
        final net.minecraft.world.level.NaturalSpawner.SpawnState state =
            level.getChunkSource().getLastSpawnState();
        final int counted = state == null ? 0 : Math.max(0, state.getMobCategoryCounts().getOrDefault(category, 0));
        return new MonsterCap(counted, 0, limit);
    }

    /**
     * Быстрая проверка, что сбор данных разрешён текущим режимом.
     */
    public static boolean available() {
        return Lab.collecting();
    }
}
