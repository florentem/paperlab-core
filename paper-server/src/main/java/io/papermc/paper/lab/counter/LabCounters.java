package io.papermc.paper.lab.counter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Реестр счётчиков воронок.
 *
 * <p>Счётчик привязан к паре «измерение + цвет шерсти»: одинаковая шерсть в аду и в овере
 * даёт разные счётчики, иначе две фермы смешиваются в одну цифру.
 *
 * <p><b>Это уничтожающий счётчик</b>, как в Carpet: предметы, попавшие в воронку,
 * направленную в шерсть, учитываются и удаляются. Пассивный счётчик реального переноса —
 * отдельная задача, он не должен подменять этот.
 */
public final class LabCounters {

    private record Key(String dimension, DyeColor color) {
    }

    private static final Map<Key, LabCounter> COUNTERS = new LinkedHashMap<>();

    private LabCounters() {
    }

    /**
     * Хук из {@code HopperBlockEntity.ejectItems}: если воронка смотрит в шерсть,
     * содержимое учитывается и очищается.
     *
     * <p>Донорская ветка Leaves {@code unlimitedSpeed} здесь <b>не воспроизводится</b>:
     * в ней {@code flag |= suckInItems(...)} делает флаг «липким», из-за чего проверка
     * {@code if (!flag) break} никогда не срабатывает и цикл выполняется до 32767 раз.
     *
     * @return {@code true}, если воронка смотрит в шерсть и обычную выгрузку делать не нужно
     */
    public static boolean consume(final Level level, final BlockPos pos, final BlockState state,
                                  final @Nullable Container container) {
        if (container == null || !(state.getBlock() instanceof HopperBlock)) {
            return false;
        }
        final DyeColor color = WoolColors.at(level, pos.relative(state.getValue(HopperBlock.FACING)));
        if (color == null) {
            return false;
        }

        final LabCounter counter = of(level, color);
        final long gameTime = level.getGameTime();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            final ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            counter.add(stack, gameTime);
            container.setItem(slot, ItemStack.EMPTY);
        }
        // true и при пустой воронке: цель направлена в шерсть, обычная выгрузка не нужна.
        return true;
    }

    public static LabCounter of(final Level level, final DyeColor color) {
        return COUNTERS.computeIfAbsent(
            new Key(dimensionName(level), color),
            key -> new LabCounter(key.color(), key.dimension()));
    }

    public static @Nullable LabCounter existing(final Level level, final DyeColor color) {
        return COUNTERS.get(new Key(dimensionName(level), color));
    }

    /** Все счётчики, у которых что-то происходило. */
    public static List<LabCounter> active() {
        final List<LabCounter> out = new ArrayList<>();
        for (final LabCounter counter : COUNTERS.values()) {
            if (counter.started()) {
                out.add(counter);
            }
        }
        return out;
    }

    public static int resetAll(final long gameTime) {
        int count = 0;
        for (final LabCounter counter : COUNTERS.values()) {
            if (counter.started()) {
                counter.reset(gameTime);
                count++;
            }
        }
        return count;
    }

    public static String dimensionName(final Level level) {
        return level.dimension().identifier().getPath();
    }
}
