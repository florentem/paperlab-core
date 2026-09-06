package io.papermc.paper.lab.spawn;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

/**
 * Outcome counters for natural spawn attempts.
 *
 * <p>Answers "why is the farm not spawning": the engine never reports where an attempt
 * stopped, and there are several reasons that mean different things.
 *
 * <p>Only facts the engine has already computed are counted. No re-checking:
 * {@code isValidSpawnPostitionForType} consumes RNG and fires an event, so it must not be
 * asked again just for statistics.
 *
 * <p>Data accumulates per world over a sliding window and is cleared when the last
 * subscription goes away.
 */
public final class SpawnTrace {

    /**
     * Where the attempt stopped.
     *
     * <p><b>The units differ, and that matters when reading them.</b> {@link #CAP_FULL} and
     * {@link #ATTEMPT} are counted per chunk x category pass of the spawn loop.
     * {@link #POSITION}, {@link #PLUGIN} and {@link #SPAWNED} are counted per individual
     * position within a pass, and several positions are tried per pass. So "position"
     * legitimately exceeds "passes", and they must not be added into one total.
     */
    public enum Outcome {
        /** The category was never considered: the chunk budget is exhausted. */
        CAP_FULL("cap"),
        /** There was budget, so attempts went ahead. */
        ATTEMPT("passes"),
        /** The position did not qualify: light, block, height, distance to a player. */
        POSITION("position"),
        /** A plugin cancelled it through PreCreatureSpawnEvent. */
        PLUGIN("plugin"),
        /** The mob was created and added to the world. */
        SPAWNED("spawned");

        private final String label;

        Outcome(final String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    private static final int OUTCOMES = Outcome.values().length;

    /** world -> category -> outcome counters. */
    private static final Map<String, Map<MobCategory, AtomicLongArray>> DATA = new java.util.concurrent.ConcurrentHashMap<>();

    /** Enabled only while someone is subscribed: otherwise the hot spawn path is left alone. */
    private static volatile boolean enabled;

    private SpawnTrace() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(final boolean value) {
        enabled = value;
        if (!value) {
            DATA.clear();
        }
    }

    public static void reset() {
        DATA.clear();
    }

    /**
     * Record an outcome. Called from hooks in {@code NaturalSpawner}.
     *
     * <p>The {@link #enabled} check comes first here and in the calling code too, so that
     * with the trace off the cost is one {@code volatile} field read.
     */
    public static void record(final ServerLevel level, final MobCategory category, final Outcome outcome) {
        if (!enabled) {
            return;
        }
        DATA.computeIfAbsent(level.dimension().identifier().toString(), key -> new java.util.concurrent.ConcurrentHashMap<>())
            .computeIfAbsent(category, key -> new AtomicLongArray(OUTCOMES))
            .incrementAndGet(outcome.ordinal());
    }

    /** Overload for places where only a chunk position is at hand. */
    public static void record(final ServerLevel level, final ChunkPos ignored,
                              final MobCategory category, final Outcome outcome) {
        record(level, category, outcome);
    }

    /**
     * A snapshot for one category in one world.
     *
     * @return an array as long as {@link Outcome#values()}, or {@code null} if there is no data
     */
    public static long[] snapshot(final ServerLevel level, final MobCategory category) {
        final Map<MobCategory, AtomicLongArray> byCategory = DATA.get(level.dimension().identifier().toString());
        if (byCategory == null) {
            return null;
        }
        final AtomicLongArray counters = byCategory.get(category);
        if (counters == null) {
            return null;
        }
        final long[] out = new long[OUTCOMES];
        for (int i = 0; i < OUTCOMES; i++) {
            out[i] = counters.get(i);
        }
        return out;
    }
}
