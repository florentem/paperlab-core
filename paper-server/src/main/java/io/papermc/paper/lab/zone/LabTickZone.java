package io.papermc.paper.lab.zone;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * A discrete ticking zone composed of any number of intersecting or disjoint 3D cuboids.
 */
public final class LabTickZone {

    public record PendingBlock(BlockPos pos, Block type) {}
    public record PendingFluid(BlockPos pos, Fluid type) {}

    private final String name;
    private final String worldKey;
    private UUID owner;
    private final Set<UUID> members = new HashSet<>();

    private final List<ZoneCuboid> boxes = new CopyOnWriteArrayList<>();
    private volatile ZoneCuboid bounds;
    private final LongSet chunks = new LongOpenHashSet();

    private volatile boolean frozen = false;
    private volatile float tickRate = 20.0F;
    private volatile int stepTicks = 0;
    private long zoneGameTime = 0L;
    private double timeAccumulator = 0.0D;

    private final List<PendingBlock> pendingBlockTicks = new ArrayList<>();
    private final List<PendingFluid> pendingFluidTicks = new ArrayList<>();

    public LabTickZone(final String name, final String worldKey, final UUID owner) {
        this.name = name;
        this.worldKey = worldKey;
        this.owner = owner;
    }

    public String name() {
        return this.name;
    }

    public String worldKey() {
        return this.worldKey;
    }

    public UUID owner() {
        return this.owner;
    }

    public void setOwner(final UUID owner) {
        this.owner = owner;
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(this.members);
    }

    public boolean isMember(final UUID uuid) {
        return (this.owner != null && this.owner.equals(uuid)) || this.members.contains(uuid);
    }

    public void addMember(final UUID uuid) {
        this.members.add(uuid);
    }

    public void removeMember(final UUID uuid) {
        this.members.remove(uuid);
    }

    public List<ZoneCuboid> boxes() {
        return Collections.unmodifiableList(this.boxes);
    }

    public synchronized void addBox(final ZoneCuboid box) {
        this.boxes.add(box);
        rebuildSpatialIndex();
    }

    public synchronized boolean removeBox(final int index) {
        if (index >= 0 && index < this.boxes.size()) {
            this.boxes.remove(index);
            rebuildSpatialIndex();
            return true;
        }
        return false;
    }

    public synchronized void clearBoxes() {
        this.boxes.clear();
        rebuildSpatialIndex();
    }

    private synchronized void rebuildSpatialIndex() {
        this.chunks.clear();
        if (this.boxes.isEmpty()) {
            this.bounds = null;
            return;
        }

        ZoneCuboid union = this.boxes.get(0);
        for (final ZoneCuboid box : this.boxes) {
            union = union.union(box);
            final int minCx = box.minX() >> 4;
            final int maxCx = box.maxX() >> 4;
            final int minCz = box.minZ() >> 4;
            final int maxCz = box.maxZ() >> 4;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    this.chunks.add(ChunkPos.pack(cx, cz));
                }
            }
        }
        this.bounds = union;
    }

    public boolean contains(final int x, final int y, final int z) {
        final ZoneCuboid b = this.bounds;
        if (b == null || !b.contains(x, y, z)) {
            return false;
        }
        for (final ZoneCuboid box : this.boxes) {
            if (box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public boolean contains(final BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean intersectsChunk(final int chunkX, final int chunkZ) {
        return this.chunks.contains(ChunkPos.pack(chunkX, chunkZ));
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    public void setFrozen(final boolean frozen) {
        this.frozen = frozen;
        if (frozen) {
            this.stepTicks = 0;
        }
    }

    public float tickRate() {
        return this.tickRate;
    }

    public void setTickRate(final float tickRate) {
        this.tickRate = Math.max(0.1F, Math.min(10000.0F, tickRate));
    }

    public int stepTicks() {
        return this.stepTicks;
    }

    public void step(final int ticks) {
        this.stepTicks += ticks;
    }

    public void stopStepping() {
        this.stepTicks = 0;
    }

    public long zoneGameTime() {
        return this.zoneGameTime;
    }

    /**
     * Whether this zone is currently permitted to tick in the current world frame.
     */
    public boolean shouldTickNow() {
        if (!this.frozen) {
            return true;
        }
        return this.stepTicks > 0;
    }

    public synchronized void recordPendingBlock(final BlockPos pos, final Block type) {
        this.pendingBlockTicks.add(new PendingBlock(pos.immutable(), type));
    }

    public synchronized void recordPendingFluid(final BlockPos pos, final Fluid type) {
        this.pendingFluidTicks.add(new PendingFluid(pos.immutable(), type));
    }

    /**
     * Advance the zone by one zone tick and dispatch any deferred ticks.
     */
    public synchronized void runZoneTick(final ServerLevel level) {
        this.zoneGameTime++;

        // Drain pending block ticks
        if (!this.pendingBlockTicks.isEmpty()) {
            final List<PendingBlock> toRun = new ArrayList<>(this.pendingBlockTicks);
            this.pendingBlockTicks.clear();
            for (final PendingBlock entry : toRun) {
                final BlockState state = level.getBlockState(entry.pos());
                if (state.is(entry.type())) {
                    state.tick(level, entry.pos(), level.getRandom());
                }
            }
        }

        // Drain pending fluid ticks
        if (!this.pendingFluidTicks.isEmpty()) {
            final List<PendingFluid> toRun = new ArrayList<>(this.pendingFluidTicks);
            this.pendingFluidTicks.clear();
            for (final PendingFluid entry : toRun) {
                final BlockState state = level.getBlockState(entry.pos());
                final FluidState fluid = state.getFluidState();
                if (fluid.is(entry.type())) {
                    fluid.tick(level, entry.pos(), state);
                }
            }
        }
    }

    /**
     * Called once per world tick from ServerLevel.tick().
     */
    public void onWorldTick(final ServerLevel level) {
        if (this.frozen) {
            if (this.stepTicks > 0) {
                this.stepTicks--;
                runZoneTick(level);
            }
            return;
        }

        // Running: rate-based pacing
        if (Math.abs(this.tickRate - 20.0F) < 0.01F) {
            // Normal 20 TPS: exactly 1 zone tick per world tick
            runZoneTick(level);
        } else {
            this.timeAccumulator += (double) this.tickRate / 20.0D;
            while (this.timeAccumulator >= 1.0D) {
                this.timeAccumulator -= 1.0D;
                runZoneTick(level);
            }
        }
    }
}
