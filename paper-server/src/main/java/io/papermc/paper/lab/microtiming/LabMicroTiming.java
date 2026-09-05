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

    public enum MarkerType {
        REGULAR,
        END_ROD
    }

    public record Marker(DyeColor color, MarkerType type) {}

    public enum CycleResultType {
        ADDED,
        SWITCHED,
        REMOVED,
        COLOR_CHANGED
    }

    public record CycleResult(CycleResultType resultType, @Nullable Marker marker) {}

    public enum Phase {
        NONE(""),
        TILE_TICK("tile_tick"),
        BLOCK_EVENT("block_event");

        private final String label;
        Phase(String label) { this.label = label; }
        public String label() { return label; }
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    public static volatile boolean enabled = false;

    private static final ThreadLocal<Integer> CALL_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Phase> CURRENT_PHASE = ThreadLocal.withInitial(() -> Phase.NONE);

    /** Маркеры красителей, установленные игроками: pos -> marker */
    private static final Map<BlockPos, Marker> DYE_MARKERS = new ConcurrentHashMap<>();

    private LabMicroTiming() {
    }

    public static int currentDepth() {
        return CALL_DEPTH.get();
    }

    public static Phase currentPhase() {
        return CURRENT_PHASE.get();
    }

    public static void pushDepth() {
        CALL_DEPTH.set(CALL_DEPTH.get() + 1);
    }

    public static void popDepth() {
        CALL_DEPTH.set(Math.max(0, CALL_DEPTH.get() - 1));
    }

    public static void setPhase(final Phase phase) {
        CURRENT_PHASE.set(phase);
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

    public static CycleResult cycleMarker(final BlockPos pos, final DyeColor color) {
        final BlockPos immutablePos = pos.immutable();
        final Marker existing = DYE_MARKERS.get(immutablePos);
        if (existing == null) {
            final Marker newMarker = new Marker(color, MarkerType.REGULAR);
            DYE_MARKERS.put(immutablePos, newMarker);
            return new CycleResult(CycleResultType.ADDED, newMarker);
        }
        if (existing.color() == color) {
            if (existing.type() == MarkerType.REGULAR) {
                final Marker switched = new Marker(color, MarkerType.END_ROD);
                DYE_MARKERS.put(immutablePos, switched);
                return new CycleResult(CycleResultType.SWITCHED, switched);
            } else {
                DYE_MARKERS.remove(immutablePos);
                return new CycleResult(CycleResultType.REMOVED, null);
            }
        } else {
            final Marker changed = new Marker(color, MarkerType.REGULAR);
            DYE_MARKERS.put(immutablePos, changed);
            return new CycleResult(CycleResultType.COLOR_CHANGED, changed);
        }
    }

    public static void setDyeMarker(final BlockPos pos, final DyeColor color) {
        DYE_MARKERS.put(pos.immutable(), new Marker(color, MarkerType.REGULAR));
    }

    public static @Nullable Marker getMarker(final BlockPos pos) {
        return DYE_MARKERS.get(pos);
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
        // 1. Проверяем маркер красителя на самом блоке
        final Marker marker = DYE_MARKERS.get(pos);
        if (marker != null) {
            return marker.color();
        }

        // Проверяем соседние блоки на наличие маркера типа END_ROD
        for (final Direction dir : Direction.values()) {
            final Marker neighborMarker = DYE_MARKERS.get(pos.relative(dir));
            if (neighborMarker != null && neighborMarker.type() == MarkerType.END_ROD) {
                return neighborMarker.color();
            }
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
