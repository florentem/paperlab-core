package io.papermc.paper.lab.cplay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Capture & Playback координатор для ServerLevel и SignalGetter.
 */
public final class CPlayManager {

    private static final Map<ResourceKey<Level>, LevelState> STATES = new ConcurrentHashMap<>();

    private CPlayManager() {
    }

    public static boolean hasPlaybackOrCapture(final ServerLevel level) {
        final LevelState state = STATES.get(level.dimension());
        return state != null && state.isActive();
    }

    public static LevelState getOrCreateState(final ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), k -> new LevelState());
    }

    public static LevelState getState(final ServerLevel level) {
        return STATES.get(level.dimension());
    }

    public static void onLevelTickHead(final ServerLevel level) {
        final LevelState state = getState(level);
        if (state == null || !state.isActive()) {
            return;
        }

        state.cleanupClosedStreams();

        if (level.tickRateManager().runsNormally() && !state.playbackStreams.isEmpty()) {
            final List<CPlaySignalEvent> merged = new ArrayList<>();
            for (final CPlayPlaybackStream stream : state.playbackStreams.values()) {
                if (!stream.isClosed()) {
                    final List<CPlaySignalEvent> events = stream.readNextTickEvents();
                    if (events != null && !events.isEmpty()) {
                        merged.addAll(events);
                    }
                }
            }
            Collections.sort(merged);
            state.signalFrame = merged;
        } else {
            state.signalFrame = Collections.emptyList();
        }

        state.frameIndex = 0;
        state.capturedEvents.clear();

        // Обрабатываем события фазы IMMEDIATE
        while (state.hasMoreSignalEvents() && state.peekSignalEvent().getPhase() == CPlayTickPhase.IMMEDIATE) {
            handleSignalEvent(level, state, state.nextSignalEvent());
        }
    }

    public static void onRunBlockEventsHead(final ServerLevel level) {
        final LevelState state = getState(level);
        if (state == null || !state.isActive()) {
            return;
        }
        state.tickPhase = CPlayTickPhase.BLOCK_EVENTS;
        state.blockEventCount = 0;
        state.microtick = -1;
    }

    public static boolean onRunBlockEventsLoop(final ServerLevel level, final int remainingQueueSize) {
        final LevelState state = getState(level);
        if (state == null || !state.isActive()) {
            return remainingQueueSize > 0;
        }

        while (state.blockEventCount == 0) {
            state.blockEventCount = remainingQueueSize;
            if (state.blockEventCount != 0) {
                state.microtick++;
            } else if (state.hasMoreSignalEvents()) {
                state.microtick = state.peekSignalEvent().getMicrotick();
            } else {
                return false;
            }
            state.handleReadySignalEvents(level);
        }
        return true;
    }

    public static void onBlockEventProcessed(final ServerLevel level) {
        final LevelState state = getState(level);
        if (state != null && state.isActive()) {
            state.blockEventCount--;
        }
    }

    public static void onBlockEventSuccess(final ServerLevel level, final BlockEventData data) {
        final LevelState state = getState(level);
        if (state == null || state.captureStreams.isEmpty()) {
            return;
        }

        final Block block = data.block();
        if (block == Blocks.PISTON || block == Blocks.STICKY_PISTON) {
            if (state.isCapturePosition(data.pos())) {
                final CPlaySignalEdge edge = (data.paramA() == 0) ? CPlaySignalEdge.RISING_EDGE : CPlaySignalEdge.FALLING_EDGE;
                state.capturedEvents.add(new CPlaySignalEvent(
                    state.tickPhase,
                    state.microtick,
                    state.capturedEvents.size(),
                    edge,
                    data.pos(),
                    false
                ));
            }
        }
    }

    public static void onRunBlockEventsReturn(final ServerLevel level) {
        final LevelState state = getState(level);
        if (state != null && state.isActive()) {
            state.microtick = -1;
        }
    }

    public static void onLevelTickReturn(final ServerLevel level) {
        final LevelState state = getState(level);
        if (state == null || state.captureStreams.isEmpty()) {
            return;
        }

        final List<CPlaySignalEvent> tickEvents = new ArrayList<>(state.signalFrame);
        tickEvents.addAll(state.capturedEvents);
        Collections.sort(tickEvents);

        for (final CPlayCaptureStream stream : state.captureStreams.values()) {
            if (!stream.isClosed()) {
                stream.writeTickEvents(tickEvents);
            }
        }
    }

    public static boolean isSignalOverridden(final SignalGetter getter, final BlockPos pos, final Direction direction) {
        if (getter instanceof ServerLevel level) {
            final LevelState state = STATES.get(level.dimension());
            if (state != null && !state.poweredStates.isEmpty()) {
                final BlockPos sourcePos = pos.relative(direction.getOpposite());
                return state.isPowering(sourcePos);
            }
        }
        return false;
    }

    private static void handleSignalEvent(final ServerLevel level, final LevelState state, final CPlaySignalEvent event) {
        final boolean rising = (event.getEdge() == CPlaySignalEdge.RISING_EDGE);
        if (rising) {
            state.incrementPowered(event.getPos());
        } else {
            state.decrementPowered(event.getPos());
        }

        if (!event.isShadow()) {
            final BlockState blockState = level.getBlockState(event.getPos());
            final Block block = blockState.getBlock();

            if (block == Blocks.PISTON || block == Blocks.STICKY_PISTON) {
                if (event.getEdge() != CPlaySignalEdge.FALLING_EDGE || blockState.getValue(PistonBaseBlock.EXTENDED)) {
                    final int type = (event.getEdge() == CPlaySignalEdge.RISING_EDGE) ? 0 : 1;
                    final int data = blockState.getValue(BlockStateProperties.FACING).get3DDataValue();
                    level.blockEvent(event.getPos(), block, type, data);
                }
            } else if (block == Blocks.TNT) {
                if (rising) {
                    level.neighborChanged(event.getPos(), Blocks.AIR, null);
                }
            } else {
                level.neighborChanged(event.getPos(), Blocks.AIR, null);
            }
        }
    }

    public static void addPlaybackStream(final ServerLevel level, final CPlayPlaybackStream stream) {
        getOrCreateState(level).playbackStreams.put(stream.getAssetId(), stream);
    }

    public static boolean removePlaybackStream(final ServerLevel level, final UUID assetId) {
        final LevelState state = getState(level);
        if (state != null) {
            final CPlayPlaybackStream removed = state.playbackStreams.remove(assetId);
            if (removed != null) {
                removed.close();
                return true;
            }
        }
        return false;
    }

    public static void addCaptureStream(final ServerLevel level, final CPlayCaptureStream stream) {
        getOrCreateState(level).captureStreams.put(stream.getAssetId(), stream);
    }

    public static boolean removeCaptureStream(final ServerLevel level, final UUID assetId) {
        final LevelState state = getState(level);
        if (state != null) {
            final CPlayCaptureStream removed = state.captureStreams.remove(assetId);
            if (removed != null) {
                removed.close();
                return true;
            }
        }
        return false;
    }

    public static Collection<CPlayPlaybackStream> getPlaybackStreams(final ServerLevel level) {
        final LevelState state = getState(level);
        return (state != null) ? Collections.unmodifiableCollection(state.playbackStreams.values()) : Collections.emptyList();
    }

    public static Collection<CPlayCaptureStream> getCaptureStreams(final ServerLevel level) {
        final LevelState state = getState(level);
        return (state != null) ? Collections.unmodifiableCollection(state.captureStreams.values()) : Collections.emptyList();
    }

    public static void clearAll(final ServerLevel level) {
        final LevelState state = getState(level);
        if (state != null) {
            for (final CPlayPlaybackStream stream : state.playbackStreams.values()) {
                stream.close();
            }
            state.playbackStreams.clear();
            for (final CPlayCaptureStream stream : state.captureStreams.values()) {
                stream.close();
            }
            state.captureStreams.clear();
            state.poweredStates.clear();
            state.capturedEvents.clear();
            state.signalFrame = Collections.emptyList();
        }
    }

    public static final class LevelState {
        final Map<UUID, CPlayPlaybackStream> playbackStreams = new HashMap<>();
        final Map<UUID, CPlayCaptureStream> captureStreams = new HashMap<>();
        final Map<BlockPos, Integer> poweredStates = new HashMap<>();
        final List<CPlaySignalEvent> capturedEvents = new ArrayList<>();
        List<CPlaySignalEvent> signalFrame = Collections.emptyList();
        int frameIndex = 0;
        int blockEventCount = 0;
        int microtick = -1;
        CPlayTickPhase tickPhase = CPlayTickPhase.BLOCK_EVENTS;

        public boolean isActive() {
            return !playbackStreams.isEmpty() || !captureStreams.isEmpty() || !poweredStates.isEmpty();
        }

        void cleanupClosedStreams() {
            playbackStreams.values().removeIf(CPlayPlaybackStream::isClosed);
            captureStreams.values().removeIf(CPlayCaptureStream::isClosed);
        }

        boolean hasMoreSignalEvents() {
            return frameIndex < signalFrame.size();
        }

        CPlaySignalEvent peekSignalEvent() {
            return hasMoreSignalEvents() ? signalFrame.get(frameIndex) : null;
        }

        CPlaySignalEvent nextSignalEvent() {
            return hasMoreSignalEvents() ? signalFrame.get(frameIndex++) : null;
        }

        boolean isEventReady(final CPlaySignalEvent event) {
            if (tickPhase.isBefore(event.getPhase())) {
                return false;
            }
            if (tickPhase == CPlayTickPhase.BLOCK_EVENTS && event.getPhase() == tickPhase) {
                return microtick >= event.getMicrotick();
            }
            return true;
        }

        void handleReadySignalEvents(final ServerLevel level) {
            while (hasMoreSignalEvents() && isEventReady(peekSignalEvent())) {
                handleSignalEvent(level, this, nextSignalEvent());
            }
        }

        boolean isCapturePosition(final BlockPos pos) {
            for (final CPlayCaptureStream stream : captureStreams.values()) {
                if (!stream.isClosed() && stream.getRegion().contains(pos)) {
                    return true;
                }
            }
            return false;
        }

        boolean isPowering(final BlockPos pos) {
            final Integer count = poweredStates.get(pos);
            return count != null && count > 0;
        }

        void incrementPowered(final BlockPos pos) {
            poweredStates.merge(pos.immutable(), 1, Integer::sum);
        }

        void decrementPowered(final BlockPos pos) {
            final Integer current = poweredStates.get(pos);
            if (current != null) {
                if (current <= 1) {
                    poweredStates.remove(pos);
                } else {
                    poweredStates.put(pos, current - 1);
                }
            }
        }
    }
}
