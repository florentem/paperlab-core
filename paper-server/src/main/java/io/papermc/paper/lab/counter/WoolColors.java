package io.papermc.paper.lab.counter;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Цвет шерсти под воронкой — триггер счётчика.
 *
 * <p>В 26.2 отдельных полей {@code Blocks.WHITE_WOOL} больше нет: шерсть собрана
 * в {@code ColorCollection<Block> Blocks.WOOL}. Поэтому обратная таблица строится
 * из самой коллекции, а не хардкодом имён — при добавлении цветов ломаться нечему.
 */
public final class WoolColors {

    private static final Map<Block, DyeColor> BY_BLOCK;

    static {
        final Map<Block, DyeColor> map = new IdentityHashMap<>(16);
        for (final DyeColor color : DyeColor.values()) {
            map.put(Blocks.WOOL.pick(color), color);
        }
        BY_BLOCK = Map.copyOf(map);
    }

    private WoolColors() {
    }

    /**
     * Читает уже загруженный блок. Чанк не грузит: для незагруженной позиции
     * возвращает {@code null}.
     */
    public static @Nullable DyeColor at(final Level level, final BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        return BY_BLOCK.get(level.getBlockState(pos).getBlock());
    }

    public static @Nullable DyeColor byName(final String name) {
        return DyeColor.byName(name.toLowerCase(Locale.ROOT), null);
    }
}
