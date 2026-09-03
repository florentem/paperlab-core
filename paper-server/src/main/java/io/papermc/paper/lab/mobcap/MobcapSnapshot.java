package io.papermc.paper.lab.mobcap;

import io.papermc.paper.lab.SpawnPhase;
import net.minecraft.world.entity.MobCategory;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Неизменяемый снимок локального мобкапа одного игрока по одной категории.
 *
 * <p>Все числа читаются из уже посчитанных движком величин. Инструмент не воспроизводит
 * арифметику спавнера и не запускает проверок заново.
 *
 * @param worldName      имя мира
 * @param playerName     имя игрока
 * @param category       категория мобов
 * @param status         применимость локальной схемы к этой категории
 * @param counted        {@code ServerPlayer.mobCounts[category]} — учтённые сущности
 * @param backoff        {@code ServerPlayer.mobBackoffCounts[category]} — штраф за неудачные попытки
 * @param limit          действующий лимит; {@code -1}, если он не применим
 * @param limitSource    откуда взят лимит
 * @param gameTime       игровой тик на момент снятия
 * @param phase          фаза тика относительно переписи и спавна
 * @param ageTicks       возраст метки фазы в тиках; {@code -1}, если фаза не фиксировалась
 */
public record MobcapSnapshot(
    String worldName,
    String playerName,
    MobCategory category,
    Status status,
    int counted,
    int backoff,
    int limit,
    LimitSource limitSource,
    long gameTime,
    SpawnPhase phase,
    long ageTicks
) {

    /**
     * Почему значения могут быть неприменимы. Вместо выдуманного нуля инструмент обязан
     * показать статус.
     */
    public enum Status {
        /** Локальная схема включена, числа действительны. */
        OK,
        /**
         * {@code entities.spawning.per-player-mob-spawns = false}: массивы {@code mobCounts}
         * и {@code mobBackoffCounts} движком <b>не обновляются вообще</b>. Любое локальное
         * число здесь — мусор из прошлого, показывать нельзя.
         */
        PER_PLAYER_DISABLED,
        /** {@code spawn-limits.<category> = 0}: категория отключена. */
        CATEGORY_DISABLED,
        /**
         * Категория не участвует в спавне и/или не имеет лимита в bukkit.yml
         * (например {@code MISC}, который исключается из переписи).
         */
        NOT_SPAWN_LIMITED
    }

    /**
     * Источник действующего лимита — ровно тот, который использует движок в
     * {@code NaturalSpawner.spawnForChunk}.
     */
    public enum LimitSource {
        /** {@code bukkit.yml → spawn-limits.<category>} (через {@code World.getSpawnLimit}). */
        BUKKIT_SPAWN_LIMITS,
        /** {@code MobCategory.getMaxInstancesPerChunk()} — категория вне лимитов bukkit.yml. */
        MOB_CATEGORY_DEFAULT,
        /** Лимит не применим. */
        NONE
    }

    /**
     * Эффективная занятость бюджета: {@code counted + backoff}.
     *
     * <p>Ровно эту сумму возвращает {@code ChunkMap.getMobCountNear}, и именно её движок
     * вычитает из лимита. Backoff — <b>не число живых мобов</b>, поэтому в UI слагаемые
     * обязаны быть видны раздельно.
     */
    public int effective() {
        return this.counted + this.backoff;
    }

    /**
     * Свободный остаток бюджета для показа: не бывает отрицательным.
     * Для диагностики превышения использовать {@link #rawFree()}.
     */
    public int free() {
        return this.limit < 0 ? -1 : Math.max(0, this.limit - this.effective());
    }

    /**
     * Сырая разница {@code limit - effective}. Может быть отрицательной — это реальное
     * превышение бюджета, и скрывать его нельзя.
     */
    public int rawFree() {
        return this.limit < 0 ? Integer.MIN_VALUE : this.limit - this.effective();
    }

    public boolean valid() {
        return this.status == Status.OK;
    }

    /**
     * Одна строка для {@code /log mobcaps}. Только форматирование, без вычислений сверх
     * уже снятых значений.
     */
    public String describe(final @Nullable String limitingPlayer, final int playersInRange) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append(this.worldName).append(" | ").append(this.playerName)
            .append(" | ").append(this.category.getName()).append(" | local");
        sb.append('\n');
        switch (this.status) {
            case PER_PLAYER_DISABLED -> sb.append(
                "  локальная схема выключена (per-player-mob-spawns=false) — использовать мировой снимок");
            case CATEGORY_DISABLED -> sb.append("  категория отключена (лимит 0)");
            case NOT_SPAWN_LIMITED -> sb.append("  категория не ограничивается spawn-limits");
            case OK -> {
                sb.append("  учтено ").append(this.counted)
                    .append(" + backoff ").append(this.backoff)
                    .append(" = эффективно ").append(this.effective())
                    .append(" / лимит ").append(this.limit)
                    .append(" (").append(this.limitSourceLabel()).append(')')
                    .append(" | остаток ").append(this.free());
                if (this.rawFree() < 0) {
                    sb.append(" [превышение ").append(-this.rawFree()).append(']');
                }
                sb.append('\n');
                if (playersInRange <= 0) {
                    sb.append("  нет игроков в области чанка — естественный спавн невозможен");
                } else if (limitingPlayer != null) {
                    sb.append("  ограничивает: ").append(limitingPlayer)
                        .append(" (из ").append(playersInRange).append(" игроков в области чанка)");
                } else {
                    sb.append("  игроков в области чанка: ").append(playersInRange);
                }
            }
        }
        sb.append('\n');
        sb.append("  tick ").append(this.gameTime)
            .append(", фаза ").append(this.phase.id())
            .append(", возраст снимка ").append(this.ageTicks < 0 ? "неизвестен" : this.ageTicks + " ticks");
        return sb.toString();
    }

    private String limitSourceLabel() {
        return switch (this.limitSource) {
            case BUKKIT_SPAWN_LIMITS -> "bukkit.yml spawn-limits";
            case MOB_CATEGORY_DEFAULT -> "MobCategory default";
            case NONE -> "нет";
        };
    }
}
