package io.papermc.paper.lab.counter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Один счётчик: цвет шерсти в одном измерении.
 *
 * <p>Отличия от донора (Leaves/Carpet), каждое — исправление найденного дефекта:
 * <ul>
 *   <li>ключ предмета включает <b>компоненты</b>, поэтому зачарованные и именованные
 *       предметы не сливаются с обычными;</li>
 *   <li>время считается по игровым тикам того мира, где стоит счётчик, и по
 *       <b>монотонным</b> наносекундам — перевод системных часов не портит рейт;</li>
 *   <li>при нулевом интервале рейт равен {@code null}, а не бесконечности.</li>
 * </ul>
 */
public final class LabCounter {

    /** Предмет вместе с компонентами: {@code DataComponentPatch} корректно сравнивается. */
    private record ItemKey(Item item, DataComponentPatch components) {
    }

    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;

    private final DyeColor color;
    private final String dimension;

    private final Map<ItemKey, Long> counts = new LinkedHashMap<>();
    private long total;

    /** {@code -1} — счётчик ещё не начинал считать. */
    private long startTick = -1L;
    private long startNanos;

    LabCounter(final DyeColor color, final String dimension) {
        this.color = color;
        this.dimension = dimension;
    }

    public DyeColor color() {
        return this.color;
    }

    public String dimension() {
        return this.dimension;
    }

    public long total() {
        return this.total;
    }

    public boolean started() {
        return this.startTick >= 0L;
    }

    void add(final ItemStack stack, final long gameTime) {
        if (stack.isEmpty()) {
            return;
        }
        if (this.startTick < 0L) {
            this.startTick = gameTime;
            this.startNanos = System.nanoTime();
        }
        final ItemKey key = new ItemKey(stack.getItem(), stack.getComponentsPatch());
        this.counts.merge(key, (long) stack.getCount(), Long::sum);
        this.total += stack.getCount();
    }

    public void reset(final long gameTime) {
        this.counts.clear();
        this.total = 0L;
        this.startTick = gameTime;
        this.startNanos = System.nanoTime();
    }

    /** Полный сброс: счётчик снова «не начинал». */
    public void clear() {
        this.counts.clear();
        this.total = 0L;
        this.startTick = -1L;
    }

    /** Прошедшие игровые тики; {@code 0}, если счётчик не начинал. */
    public long elapsedTicks(final long gameTime) {
        return this.startTick < 0L ? 0L : Math.max(0L, gameTime - this.startTick);
    }

    /** Реальные секунды по монотонным часам — для сверки с игровым временем. */
    public double elapsedRealSeconds() {
        return this.startTick < 0L ? 0.0D : (System.nanoTime() - this.startNanos) / 1_000_000_000.0D;
    }

    /**
     * Предметов в час по игровому времени.
     *
     * @return {@code null}, если интервал нулевой — делить нельзя, и выдумывать число нельзя
     */
    public Double perHour(final long gameTime) {
        final long ticks = this.elapsedTicks(gameTime);
        if (ticks <= 0L) {
            return null;
        }
        return this.total * (double) TICKS_PER_HOUR / ticks;
    }

    /** Разбивка по предметам, по убыванию количества. */
    public List<Entry> entries() {
        final List<Entry> out = new ArrayList<>(this.counts.size());
        this.counts.forEach((key, count) ->
            out.add(new Entry(new ItemStack(key.item()).getHoverName(), count)));
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }

    public record Entry(Component name, long count) {
    }
}
