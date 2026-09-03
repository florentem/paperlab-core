package io.papermc.paper.lab.activation;

import io.papermc.paper.entity.activation.ActivationType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;

/**
 * Снимок состояния Entity Activation Range одной сущности.
 *
 * <p><b>Только чтение уже принятого решения.</b> Повторный вызов
 * {@code ActivationRange.checkIfActive(entity)} недопустим: он изменяет
 * {@code entity.activatedTick} и {@code entity.isTemporarilyActive}, то есть наблюдение
 * поменяло бы наблюдаемое. Поэтому инспектор читает поля напрямую и, где решение
 * невыводимо без побочных эффектов, честно говорит «неизвестно».
 *
 * <p>Важное разграничение: EAR действует <b>только внутри entity-ticking чанка</b>.
 * Если чанк не entity-ticking, сущность вообще не попадает в цикл активации, и EAR
 * не проверяется — заморозка сильнее EAR. Флаг {@link #entityTicking} обязателен в выводе.
 *
 * @param entityType      тип сущности
 * @param activationType  категория EAR (определяет радиус из spigot.yml)
 * @param entityTicking   находится ли сущность в entity-ticking чанке
 * @param alwaysActive    сущность помечена {@code defaultActivationState} — EAR её не усыпляет
 * @param active          активна ли сущность в текущем тике по записанному решению
 * @param temporarilyActive  движок выдал контрольный полный тик неактивной сущности
 * @param activatedTick   тик, до которого действует активность
 * @param ticksRemaining  сколько тиков активности осталось; {@code <= 0} — активности нет
 * @param currentTick     {@code MinecraftServer.currentTick} на момент снятия
 * @param hardImmunity    причина безусловной активности, если она очевидна без побочных эффектов
 */
public record EarSnapshot(
    String entityType,
    ActivationType activationType,
    boolean entityTicking,
    boolean alwaysActive,
    boolean active,
    boolean temporarilyActive,
    long activatedTick,
    long ticksRemaining,
    long currentTick,
    HardImmunity hardImmunity
) {

    /**
     * Безусловные иммунитеты из начала {@code checkIfActive}, которые можно определить
     * чистым чтением состояния — без вызова {@code checkEntityImmunities}, у которого
     * есть побочные эффекты.
     */
    public enum HardImmunity {
        NONE("нет"),
        FIREWORK("фейерверк — никогда не усыпляется"),
        ITEM_GRAVITY_TICK("предмет: тик гравитации (tickCount+id)%4==0"),
        DEFAULT_ACTIVATION_STATE("always-active по типу (лодки, вагонетки, TNT, снаряды и др.)"),
        NEW_ENTITY("новая сущность: первые 200 тиков"),
        NOT_ALIVE("не жива"),
        PORTAL("активный portalProcess или portal cooldown"),
        LEASHED_TO_PLAYER("привязана поводком к игроку");

        private final String description;

        HardImmunity(final String description) {
            this.description = description;
        }

        public String description() {
            return this.description;
        }
    }

    /**
     * Снимает состояние без побочных эффектов.
     *
     * @param entity        сущность
     * @param entityTicking результат внешней проверки, находится ли чанк сущности
     *                      в entity-ticking состоянии; вычисляется вызывающей стороной,
     *                      чтобы этот класс не трогал chunk-систему
     */
    public static EarSnapshot of(final Entity entity, final boolean entityTicking) {
        final long currentTick = MinecraftServer.currentTick;
        final long activatedTick = entity.activatedTick;

        // Порядок проверок повторяет checkIfActive, но выполняется только для тех условий,
        // чтение которых не имеет побочных эффектов.
        final HardImmunity immunity;
        if (entity instanceof FireworkRocketEntity) {
            immunity = HardImmunity.FIREWORK;
        } else if (entity instanceof ItemEntity && (entity.tickCount + entity.getId()) % 4 == 0) {
            immunity = HardImmunity.ITEM_GRAVITY_TICK;
        } else if (entity.defaultActivationState) {
            immunity = HardImmunity.DEFAULT_ACTIVATION_STATE;
        } else if (entity.tickCount < 20 * 10) {
            immunity = HardImmunity.NEW_ENTITY;
        } else if (!entity.isAlive()) {
            immunity = HardImmunity.NOT_ALIVE;
        } else if ((entity.portalProcess != null && !entity.portalProcess.hasExpired())
            || entity.getPortalCooldown() > 0) {
            immunity = HardImmunity.PORTAL;
        } else if (entity instanceof final Mob mob && mob.getLeashHolder() instanceof Player) {
            immunity = HardImmunity.LEASHED_TO_PLAYER;
        } else {
            immunity = HardImmunity.NONE;
        }

        final boolean tickWindowActive = activatedTick >= currentTick;
        final boolean active = immunity != HardImmunity.NONE || tickWindowActive;

        return new EarSnapshot(
            net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString(),
            entity.activationType,
            entityTicking,
            entity.defaultActivationState,
            active,
            entity.isTemporarilyActive,
            activatedTick,
            activatedTick == Integer.MIN_VALUE ? 0 : Math.max(0L, activatedTick - currentTick),
            currentTick,
            immunity
        );
    }

    /**
     * Одна короткая строка.
     */
    public String line() {
        if (!this.entityTicking) {
            return this.entityType + " frozen (chunk not entity-ticking)";
        }
        final StringBuilder sb = new StringBuilder(48);
        sb.append(this.entityType).append(' ')
            .append(this.active ? "active" : "inactive");
        if (this.temporarilyActive) {
            sb.append(" (probe tick)");
        }
        if (this.hardImmunity != HardImmunity.NONE) {
            sb.append(" [").append(this.hardImmunity.name().toLowerCase(java.util.Locale.ROOT)).append(']');
        } else if (this.active) {
            sb.append(" ").append(this.ticksRemaining).append("t");
        }
        return sb.toString();
    }
}
