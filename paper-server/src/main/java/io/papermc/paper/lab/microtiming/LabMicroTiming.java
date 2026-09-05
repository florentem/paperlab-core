package io.papermc.paper.lab.microtiming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Микротайминг редстоуна и событий блоков (/log microtiming).
 */
public final class LabMicroTiming {

    public interface Listener {
        void onBlockStateChange(Level level, BlockPos pos, BlockState oldState, BlockState newState, DyeColor color);
        void onBlockEvent(Level level, BlockPos pos, Block block, int type, int data, DyeColor color);
        void onTileTick(Level level, BlockPos pos, Block block, DyeColor color);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    public static volatile boolean enabled = false;

    /** Маркеры красителей, установленные игроками: pos -> color */
    private static final Map<BlockPos, DyeColor> DYE_MARKERS = new ConcurrentHashMap<>();

    private LabMicroTiming() {
    }

    public static void addListener(final Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(final Listener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean hasListeners() {
        return enabled && !LISTENERS.isEmpty();
    }

    public static void setDyeMarker(final BlockPos pos, final DyeColor color) {
        DYE_MARKERS.put(pos.immutable(), color);
    }

    public static void removeDyeMarker(final BlockPos pos) {
        DYE_MARKERS.remove(pos);
    }

    public static void clearDyeMarkers() {
        DYE_MARKERS.clear();
    }

    public static @Nullable DyeColor getTrackedColor(final Level level, final BlockPos pos) {
        if (!hasListeners()) {
            return null;
        }
        // 1. Проверяем маркер красителя
        final DyeColor dye = DYE_MARKERS.get(pos);
        if (dye != null) {
            return dye;
        }

        // 2. Проверяем маркировку шерстью как в Carpet-TIS-Addition
        final BlockState state = level.getBlockState(pos);
        final Block block = state.getBlock();

        BlockPos woolPos = null;

        if (block instanceof ObserverBlock || block instanceof EndRodBlock
            || block instanceof PistonBaseBlock || block instanceof MovingPistonBlock) {
            if (state.hasProperty(BlockStateProperties.FACING)) {
                woolPos = pos.relative(state.getValue(BlockStateProperties.FACING).getOpposite());
            }
        } else if (block instanceof ButtonBlock || block instanceof LeverBlock) {
            if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                final AttachFace face = state.getValue(BlockStateProperties.ATTACH_FACE);
                Direction facing = Direction.UP;
                if (face == AttachFace.FLOOR) {
                    facing = Direction.UP;
                } else if (face == AttachFace.CEILING) {
                    facing = Direction.DOWN;
                } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                }
                woolPos = pos.relative(facing.getOpposite());
            }
        } else if (block instanceof RedstoneWallTorchBlock || block instanceof TripWireHookBlock) {
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                woolPos = pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
            }
        } else if (block instanceof BaseRailBlock
            || block instanceof DiodeBlock
            || block instanceof RedstoneTorchBlock
            || block instanceof RedStoneWireBlock
            || block instanceof BasePressurePlateBlock) {
            woolPos = pos.below();
        }

        if (woolPos != null) {
            final DyeColor woolColor = getWoolBlockColor(level.getBlockState(woolPos));
            if (woolColor != null) {
                return woolColor;
            }
        }

        // Проверка End Rod, указывающего на блок, с шерстью позади
        for (final Direction dir : Direction.values()) {
            final BlockPos rodPos = pos.relative(dir);
            final BlockState rodState = level.getBlockState(rodPos);
            if (rodState.is(Blocks.END_ROD) && rodState.hasProperty(DirectionalBlock.FACING)
                && rodState.getValue(DirectionalBlock.FACING).getOpposite() == dir) {
                final BlockPos rodWoolPos = rodPos.relative(dir);
                final DyeColor c = getWoolBlockColor(level.getBlockState(rodWoolPos));
                if (c != null) {
                    return c;
                }
            }
        }

        return null;
    }

    private static @Nullable DyeColor getWoolBlockColor(final BlockState state) {
        final Block b = state.getBlock();
        for (final DyeColor color : DyeColor.values()) {
            if (Blocks.WOOL.pick(color) == b) {
                return color;
            }
        }
        return null;
    }

    public static void onBlockStateChange(final Level level, final BlockPos pos,
                                          final BlockState oldState, final BlockState newState) {
        if (!hasListeners()) {
            return;
        }
        final DyeColor color = getTrackedColor(level, pos);
        if (color != null) {
            for (final Listener listener : LISTENERS) {
                listener.onBlockStateChange(level, pos, oldState, newState, color);
            }
        }
    }

    public static void onBlockEvent(final Level level, final BlockPos pos,
                                    final Block block, final int type, final int data) {
        if (!hasListeners()) {
            return;
        }
        final DyeColor color = getTrackedColor(level, pos);
        if (color != null) {
            for (final Listener listener : LISTENERS) {
                listener.onBlockEvent(level, pos, block, type, data, color);
            }
        }
    }

    public static void onTileTick(final Level level, final BlockPos pos, final Block block) {
        if (!hasListeners()) {
            return;
        }
        final DyeColor color = getTrackedColor(level, pos);
        if (color != null) {
            for (final Listener listener : LISTENERS) {
                listener.onTileTick(level, pos, block, color);
            }
        }
    }
}
