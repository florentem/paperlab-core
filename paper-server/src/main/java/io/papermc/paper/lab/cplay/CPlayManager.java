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
 * Capture &amp; Playback coordinator for ServerLevel and SignalGetter.
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
        final LevelState state = STATES.computeIfAbsent(level.dimension(), k -> new LevelState());
        cachedLevel = null; // the cache may hold a null for this level
        return state;
    }

    public static LevelState getState(final ServerLevel level) {
        return STATES.get(level.dimension());
    }

    public static void onLevelTickHead(final ServerLevel level) {
        if (STATES.isEmpty()) {
            return;
        }
        final LevelState state = getState(level);
        if (state == null || !state.isActive()) {
            return;
        }

        state.cleanupClosedStreams();

        // A playback that ended, was stopped, or was cut short can leave positions still
        // marked as powered: a rising edge whose falling edge never arrived. The override in
        // isSignalOverridden then keeps feeding those positions signal 15 forever, and a
        // piston placed there stays extended with nothing powering it. Seen on the bench.
        //
        // With no playback streams left there is nothing that could legitimately hold a
        // position powered, so the map is released and the affected blocks are given a
        // neighbour update - clearing the override alone would not retract the piston,
        // because nothing would ask it to re-evaluate.
        if (state.playbackStreams.isEmpty() && !state.poweredStates.isEmpty()) {
            releaseStuckSignals(level, state);
        }

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

        // Handle IMMEDIATE-phase events.
        while (state.hasMoreSignalEvents() && state.peekSignalEvent().getPhase() == CPlayTickPhase.IMMEDIATE) {
            handleSignalEvent(level, state, state.nextSignalEvent());
        }
    }

    public static void onRunBlockEventsHead(final ServerLevel level) {
        if (STATES.isEmpty()) {
            return;
        }
        final LevelState state = getState(level);
        if (state == null || !state.isActive()) {
            return;
        }
        state.tickPhase = CPlayTickPhase.BLOCK_EVENTS;
        state.blockEventCount = 0;
        state.microtick = -1;
    }

    public static boolean onRunBlockEventsLoop(final ServerLevel level, final int remainingQueueSize) {
        if (STATES.isEmpty()) {
            return remainingQueueSize > 0;
        }
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
        if (STATES.isEmpty()) {
            return;
        }
        final LevelState state = getState(level);
        if (state != null && state.isActive()) {
            state.blockEventCount--;
        }
    }

    public static void onBlockEventSuccess(final ServerLevel level, final BlockEventData data) {
        if (STATES.isEmpty()) {
            return;
        }
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

    /**
     * Record a redstone signal changing at a captured position.
     *
     * <p>Called from {@code Level.setBlock}, the one place every block state change goes through.
     * Until this existed a capture only saw piston block events, so a lever, a repeater or a line
     * of dust recorded nothing at all and a recording of them played back as silence.
     *
     * <p>Only <b>inputs</b> are recorded — a lever thrown, a plate stepped on, an observer
     * firing. Not dust, repeaters, comparators or torches: those the circuit works out for
     * itself, and replaying them instead of the input reproduces the effect rather than the
     * cause. See {@link #isInput}.
     *
     * <p>The comparison is "emits anything" against "emits nothing", not the signal strength: the
     * playback override is binary, it can hold a position at 15 or leave it alone. A dust line
     * fading from 15 to 14 is therefore not an event, which is right — nothing switched.
     */
    public static void onBlockStateChanged(final Level level, final BlockPos pos,
                                           final BlockState oldState, final BlockState newState) {
        if (STATES.isEmpty() || oldState == newState || !(level instanceof final ServerLevel serverLevel)) {
            return;
        }
        final LevelState state = getState(serverLevel);
        if (state == null || state.captureStreams.isEmpty() || !state.isCapturePosition(pos)) {
            return;
        }

        final boolean before = emitsSignal(serverLevel, pos, oldState);
        final boolean after = emitsSignal(serverLevel, pos, newState);
        if (before == after) {
            return;
        }

        state.capturedEvents.add(new CPlaySignalEvent(
            state.tickPhase,
            state.microtick,
            state.capturedEvents.size(),
            after ? CPlaySignalEdge.RISING_EDGE : CPlaySignalEdge.FALLING_EDGE,
            pos.immutable(),
            false
        ));
    }

    /**
     * Whether this state emits redstone in any direction.
     *
     * <p>{@code isSignalSource} comes first because it is a field read, while {@code getSignal}
     * can look at the world; this runs inside setBlock, so the cheap check has to be the one that
     * rejects almost everything.
     */
    private static boolean emitsSignal(final ServerLevel level, final BlockPos pos, final BlockState state) {
        if (state.isAir() || !state.isSignalSource() || !isInput(state)) {
            return false;
        }
        for (final Direction direction : Direction.values()) {
            if (state.getSignal(level, pos, direction) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this block is an <b>input</b> to a circuit rather than something the circuit works
     * out for itself.
     *
     * <p>We record what the player and the world do — a lever thrown, a plate stepped on, an
     * observer firing — and not dust, repeaters, comparators or torches. Replaying an input and
     * letting redstone recompute the rest reproduces the original run; replaying every component
     * does not, for two reasons.
     *
     * <p>The first is visible on the bench: the override is omnidirectional, so a replayed
     * repeater powers the dust beside it, which vanilla would never do — a repeater only outputs
     * forward. The second is that a replayed component fights the one the circuit is computing at
     * the same time, and which of the two wins is not something a recording should decide.
     *
     * <p>The cost is that a recording cannot capture the output of a subsystem on its own; it
     * captures what drove it. For reproducing a run that is the right half. Recording components
     * faithfully would mean storing the direction of every edge and making the override
     * directional — worth doing if it is ever needed, but it is a different feature.
     */
    private static boolean isInput(final BlockState state) {
        final Block block = state.getBlock();
        return block instanceof net.minecraft.world.level.block.LeverBlock
            || block instanceof net.minecraft.world.level.block.ButtonBlock
            || block instanceof net.minecraft.world.level.block.BasePressurePlateBlock
            || block instanceof net.minecraft.world.level.block.TripWireHookBlock
            || block instanceof net.minecraft.world.level.block.DaylightDetectorBlock
            || block instanceof net.minecraft.world.level.block.DetectorRailBlock
            || block instanceof net.minecraft.world.level.block.ObserverBlock
            || block instanceof net.minecraft.world.level.block.LightningRodBlock
            || block instanceof net.minecraft.world.level.block.SculkSensorBlock
            || block instanceof net.minecraft.world.level.block.TargetBlock
            || block instanceof net.minecraft.world.level.block.TrappedChestBlock;
    }

    public static void onRunBlockEventsReturn(final ServerLevel level) {
        if (STATES.isEmpty()) {
            return;
        }
        final LevelState state = getState(level);
        if (state != null && state.isActive()) {
            state.microtick = -1;
        }
    }

    public static void onLevelTickReturn(final ServerLevel level) {
        if (STATES.isEmpty()) {
            return;
        }
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

        // Cleared here, after writing, and deliberately not at the head of the tick.
        //
        // Only part of the world's block changes happen inside the level tick. A player flipping
        // a lever is handled in the connection phase, which runs after every level has ticked,
        // so an edge recorded there would be wiped at the next tick head before it was ever
        // written. That is why a capture used to see pistons - their block events happen inside
        // the tick - and nothing a player touched.
        //
        // Carrying such an edge into the next frame is also the truthful placement: redstone
        // driven by a player's click propagates on the following tick anyway.
        state.capturedEvents.clear();
    }

    public static boolean isSignalOverridden(final SignalGetter getter, final BlockPos pos, final Direction direction) {
        if (STATES.isEmpty()) {
            return false;
        }
        if (getter instanceof ServerLevel level) {
            final LevelState state = stateOf(level);
            if (state != null && !state.poweredStates.isEmpty()) {
                // The block being asked is the one that emits. This used to look at
                // pos.relative(direction.getOpposite()) instead, which is the opposite meaning:
                // "this position receives power". That worked for a piston, because a recording
                // held the piston's own position and the point was to make the piston see a
                // powered neighbour. It does nothing for a lever, a repeater or dust - a source
                // ignores its input - which is why a redstone recording played back as silence
                // even once the capture was recording it correctly.
                return state.isPowering(pos);
            }
        }
        return false;
    }

    /**
     * The level's state, with a one-entry cache in front of the map.
     *
     * <p>Measured on the bench: a playback running in <i>any</i> world cost every other world
     * 0.37 ms per tick — 2.48 against 2.85 over three alternations on a field of 1681 redstone
     * dust. The playback was in the nether and the load in the overworld, so all of it was the
     * lookup: {@code isSignalOverridden} runs on every redstone signal query in the game, and
     * once {@code STATES} is no longer empty each of those calls did a concurrent map get.
     *
     * <p>Levels tick one at a time, so a single-entry cache hits almost always and turns the
     * lookup into a reference comparison. Caching a {@code null} state matters just as much as
     * caching a real one: a world with no playback is the common case, and that is the case that
     * must be free.
     *
     * <p>Both fields are written together and only ever read as a pair through this method; a
     * stale pair would at worst cost one extra map lookup, never a wrong answer, because the
     * level is compared by identity.
     */
    private static ServerLevel cachedLevel;
    private static LevelState cachedState;

    private static LevelState stateOf(final ServerLevel level) {
        if (cachedLevel == level) {
            return cachedState;
        }
        final LevelState state = STATES.get(level.dimension());
        cachedState = state;
        cachedLevel = level;
        return state;
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
                // Tell the neighbours, not the block itself. The override makes this position
                // emit; nothing would notice until something asked, and a lamp next to it only
                // asks when it is told to re-evaluate.
                level.updateNeighborsAt(event.getPos(), blockState.getBlock());
                level.neighborChanged(event.getPos(), Blocks.AIR, null);
            }
        }
    }

    /**
     * Drop every position left marked as powered and let the world recompute.
     *
     * <p>Called when the last playback stream is gone. Without it the signal override
     * outlives the playback that created it.
     */
    private static void releaseStuckSignals(final ServerLevel level, final LevelState state) {
        final List<BlockPos> stuck = new ArrayList<>(state.poweredStates.keySet());
        state.poweredStates.clear();
        for (final BlockPos pos : stuck) {
            level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
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
            releaseStuckSignals(level, state);
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
