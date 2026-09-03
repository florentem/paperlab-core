package io.papermc.paper.lab;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Хранит фазу спавна и номер тика на момент последнего хука — по одному значению на мир.
 *
 * <p>Только запись факта. Никаких вычислений, RNG, tickets и загрузки чанков.
 * Если хук не вызывался, {@link #phaseOf} честно возвращает {@link SpawnPhase#UNKNOWN}.
 */
public final class SpawnPhaseTracker {

    private record State(SpawnPhase phase, long gameTime, long nanos) {
    }

    private static final Map<ResourceKey<Level>, State> STATES = new ConcurrentHashMap<>();

    private SpawnPhaseTracker() {
    }

    /**
     * Вызывается хуком из движка. Без {@link Lab#collecting()} не делает ничего.
     */
    public static void mark(final ServerLevel level, final SpawnPhase phase) {
        if (!Lab.collecting()) {
            return;
        }
        STATES.put(level.dimension(), new State(phase, level.getGameTime(), System.nanoTime()));
    }

    public static SpawnPhase phaseOf(final ServerLevel level) {
        final State state = STATES.get(level.dimension());
        return state == null ? SpawnPhase.UNKNOWN : state.phase();
    }

    /**
     * Возраст снимка в тиках: сколько тиков прошло с момента, когда фаза была зафиксирована.
     * Отрицательных значений не бывает; {@code -1} означает «фаза не фиксировалась».
     */
    public static long ageInTicks(final ServerLevel level) {
        final State state = STATES.get(level.dimension());
        if (state == null) {
            return -1L;
        }
        final long age = level.getGameTime() - state.gameTime();
        return Math.max(0L, age);
    }

    /**
     * Смена режима или остановка мира делает накопленные метки несопоставимыми.
     */
    public static void invalidate() {
        STATES.clear();
    }

    public static void invalidate(final ServerLevel level) {
        STATES.remove(level.dimension());
    }
}
