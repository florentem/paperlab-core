package io.papermc.paper.lab.ghost;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Режим наблюдателя: игрок перестаёт влиять на симуляцию, но продолжает
 * взаимодействовать с миром.
 *
 * <p>Задача — летать вдоль границы чанков и разбирать конструкцию фермы, пока ферму
 * обслуживает бот, и при этом не искажать то, что измеряешь: не грузить чанки, не занимать
 * мобкап, не сдвигать границу спавна, не будить мобов и не мешать деспавну.
 *
 * <p><b>Чего режим НЕ делает.</b> Это не spectator: блоки ставятся и ломаются, контейнеры
 * открываются, инвентарь работает. Отключено только влияние на серверную симуляцию.
 *
 * <h2>Из чего складывается</h2>
 * <ol>
 *   <li><b>Чанки не тикают</b> — персональная дистанция симуляции ставится в {@code 0},
 *       плюс {@code ChunkMap.skipPlayer} возвращает {@code true}.
 *       <p>Одного {@code skipPlayer} <b>недостаточно</b>, и это была настоящая ошибка:
 *       он гасит только легаси-{@code DistanceManager}, а реальную загрузку в Paper делает
 *       {@code moonrise$getPlayerChunkLoader()}, который в {@code updatePlayerStatus}
 *       вызывается <i>вне</i> проверки {@code ignored}. Поэтому наблюдатель продолжал
 *       делать чанки вокруг себя entity-ticking, и мобы рядом с ним оживали.
 *       <p>С нулевой дистанцией симуляции наблюдатель добавляет тикеты только уровня FULL.
 *       Такой тикет не даёт ни block-ticking, ни entity-ticking, поэтому изменить
 *       геометрию ticking у бота он не может — а мир при этом продолжает отображаться.
 *       <p>Полного нуля Moonrise не поддерживает: отрицательное значение означает
 *       «наследовать мировое», поэтому под самим наблюдателем остаётся один
 *       entity-ticking чанк. Это известное ограничение, а не недосмотр.</li>
 *   <li><b>Мобкап и радиус спавна</b> — игрок пропускается в четырёх местах чтения:
 *       перепись {@code ChunkMap.updatePlayerMobTypeMap}, начисление backoff
 *       {@code updateFailurePlayerMobTypeMap}, поиск минимального остатка в
 *       {@code NaturalSpawner.spawnForChunk} и отбор чанков в {@code isChunkNearPlayer}.
 *       <p><b>Удалять игрока из {@code NearbyPlayers} нельзя</b>, хотя это и выглядит
 *       как одна точка вместо четырёх. Карта управляется жизненным циклом сущности:
 *       {@code tickPlayer} на каждом перемещении бросает
 *       {@code IllegalStateException: Don't have player}, а вместе с этим ломается
 *       трекинг сущностей — игрок перестаёт видеть остальных. Проверено на живом сервере.</li>
 *   <li><b>Деспавн, позиция спавна, trial spawner</b> — через штатный флаг Paper
 *       {@code Player.affectsSpawning}. Патч не нужен: его уже проверяют
 *       {@code EntitySelector.PLAYER_AFFECTS_SPAWNING} и
 *       {@code EntityGetter.getNearestPlayerAffectingSpawning}.</li>
 *   <li><b>EAR</b> — игрок не участвует в построении областей активации, иначе он будил бы
 *       мобов вокруг себя.</li>
 *   <li><b>Мобы не замечают</b> — {@code LivingEntity.canBeSeenByAnyone} возвращает
 *       {@code false}. Это единственная точка входа в {@code TargetingConditions.test},
 *       поэтому ни один AI-селектор такого игрока не выберет.</li>
 * </ol>
 *
 * <p>Состояние держится в памяти и сбрасывается при перезапуске: это режим отладки,
 * а не свойство игрока, и переживать рестарт он не должен.
 */
public final class LabGhost {

    private static final Set<UUID> GHOSTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private LabGhost() {
    }

    public static boolean isGhost(final Entity entity) {
        return !GHOSTS.isEmpty()
            && entity instanceof ServerPlayer
            && GHOSTS.contains(entity.getUUID());
    }

    public static boolean isGhost(final UUID uuid) {
        return GHOSTS.contains(uuid);
    }

    public static boolean any() {
        return !GHOSTS.isEmpty();
    }

    /**
     * Переключает режим.
     *
     * @return {@code true}, если режим теперь включён
     */
    public static boolean toggle(final ServerPlayer player) {
        return set(player, !GHOSTS.contains(player.getUUID()));
    }

    public static boolean set(final ServerPlayer player, final boolean ghost) {
        final ServerLevel level = player.level();
        if (ghost) {
            GHOSTS.add(player.getUUID());
        } else {
            GHOSTS.remove(player.getUUID());
        }

        // Штатный флаг Paper: деспавн, выбор позиции спавна, trial spawner.
        player.affectsSpawning = !ghost;

        // Тикающие чанки: 0 — минимум, который поддерживает Moonrise.
        // -1 возвращает игрока к мировому значению.
        io.papermc.paper.FeatureHooks.setSimulationDistance(player, ghost ? 0 : -1);

        // Пересчитать участие в загрузке.
        //
        // ЗАМЕРЕНО: включение режима действует НЕ мгновенно. Значение sim=0 применяется
        // сразу, но снятие уже выданных ticking-тикетов у Moonrise идёт отложенно и с
        // ограничением скорости — на стенде с 121 чанка до 1 сходится примерно за 30 секунд.
        // Выключение, наоборот, срабатывает за секунды.
        //
        // Принудительная переустановка игрока в загрузчике чанков
        // (removePlayerFromDistanceMaps + addPlayerToDistanceMaps) пробовалась и задержку
        // НЕ убирает: очередь снятия тикетов всё равно рассасывается постепенно.
        // Поэтому лишней churn-логики здесь нет — после включения просто нужно подождать.
        level.getChunkSource().chunkMap.move(player);

        // Скрываем от чужих глаз: для наблюдателя это удобно, а на симуляцию не влияет.
        player.setInvisible(ghost);
        return ghost;
    }

    /** Снять режим при выходе, иначе состояние переживёт переподключение. */
    public static void onDisconnect(final ServerPlayer player) {
        if (GHOSTS.remove(player.getUUID())) {
            player.affectsSpawning = true;
        }
    }

    public static int count() {
        return GHOSTS.size();
    }
}
