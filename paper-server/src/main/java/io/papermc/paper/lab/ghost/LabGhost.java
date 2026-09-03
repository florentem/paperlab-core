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
 *   <li><b>Чанки не грузятся</b> — {@code ChunkMap.skipPlayer} возвращает {@code true},
 *       ровно тот же путь, которым Paper уже игнорирует spectator'ов при выключенном
 *       {@code spectatorsGenerateChunks}. Игрок не попадает ни в {@code DistanceManager},
 *       ни в ticking-трекер.</li>
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

    private static final Set<UUID> GHOSTS = new HashSet<>();

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

        // Пересчитать участие в загрузке чанков: skipPlayer теперь отвечает иначе,
        // поэтому игрока надо провести через штатный путь обновления.
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
