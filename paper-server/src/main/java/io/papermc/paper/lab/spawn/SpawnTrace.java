package io.papermc.paper.lab.spawn;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

/**
 * Счётчики исходов попыток естественного спавна.
 *
 * <p>Отвечает на вопрос «почему ферма не спавнит»: движок не сообщает, где именно
 * остановилась попытка, а причин несколько и они разные по смыслу.
 *
 * <p>Считаем только факты, которые движок уже вычислил. Никаких повторных проверок:
 * {@code isValidSpawnPostitionForType} потребляет RNG и вызывает событие, поэтому
 * переспрашивать его ради статистики нельзя.
 *
 * <p>Данные копятся по миру за скользящее окно и обнуляются при снятии подписки.
 */
public final class SpawnTrace {

    /**
     * Где остановилась попытка.
     *
     * <p><b>Единицы разные, и это важно при чтении.</b> {@link #CAP_FULL} и {@link #ATTEMPT}
     * считаются на каждый проход «чанк x категория» в цикле спавна. {@link #POSITION},
     * {@link #PLUGIN} и {@link #SPAWNED} — на каждую отдельную позицию внутри прохода,
     * а позиций за проход пробуется несколько. Поэтому «позиция» законно бывает больше
     * «проходов», и складывать их в одну сумму нельзя.
     */
    public enum Outcome {
        /** Категория даже не рассматривалась: бюджет чанка исчерпан. */
        CAP_FULL("кап"),
        /** Бюджет был, попытки пошли. */
        ATTEMPT("проходов"),
        /** Позиция не подошла: свет, блок, высота, расстояние до игрока. */
        POSITION("позиция"),
        /** Плагин отменил через PreCreatureSpawnEvent. */
        PLUGIN("плагин"),
        /** Моб создан и добавлен в мир. */
        SPAWNED("заспавнено");

        private final String label;

        Outcome(final String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    private static final int OUTCOMES = Outcome.values().length;

    /** мир → категория → счётчики исходов. */
    private static final Map<String, Map<MobCategory, AtomicLongArray>> DATA = new java.util.concurrent.ConcurrentHashMap<>();

    /** Включается только когда кто-то подписан: иначе горячий путь спавна не трогаем. */
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
     * Записать исход. Вызывается из хуков в {@code NaturalSpawner}.
     *
     * <p>Проверка {@link #enabled} стоит первой и в вызывающем коде тоже — чтобы при
     * выключенной трассе стоимость сводилась к чтению одного {@code volatile} поля.
     */
    public static void record(final ServerLevel level, final MobCategory category, final Outcome outcome) {
        if (!enabled) {
            return;
        }
        DATA.computeIfAbsent(level.dimension().identifier().getPath(), key -> new EnumMap<>(MobCategory.class))
            .computeIfAbsent(category, key -> new AtomicLongArray(OUTCOMES))
            .incrementAndGet(outcome.ordinal());
    }

    /** Перегрузка для мест, где под рукой только позиция чанка. */
    public static void record(final ServerLevel level, final ChunkPos ignored,
                              final MobCategory category, final Outcome outcome) {
        record(level, category, outcome);
    }

    /**
     * Снимок по одной категории в одном мире.
     *
     * @return массив длиной {@link Outcome#values()}, либо {@code null}, если данных нет
     */
    public static long[] snapshot(final ServerLevel level, final MobCategory category) {
        final Map<MobCategory, AtomicLongArray> byCategory = DATA.get(level.dimension().identifier().getPath());
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
