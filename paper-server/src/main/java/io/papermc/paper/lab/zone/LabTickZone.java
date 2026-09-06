package io.papermc.paper.lab.zone;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * A discrete ticking zone composed of any number of intersecting or disjoint 3D cuboids.
 */
public final class LabTickZone {

    private final String name;
    private final String worldKey;
    private UUID owner;
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();

    private final List<ZoneCuboid> boxes = new CopyOnWriteArrayList<>();
    private volatile ZoneCuboid bounds;
    private volatile LongSet chunks = new LongOpenHashSet();

    private volatile boolean frozen = false;
    private volatile float tickRate = 20.0F;
    private volatile int stepTicks = 0;
    private long zoneGameTime = -1L;
    private double timeAccumulator = 0.0D;

    private volatile int ticksToRunThisFrame = 0;

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
        LabTickZones.rebuildChunkIndex(this.worldKey);
    }

    public synchronized boolean removeBox(final int index) {
        if (index >= 0 && index < this.boxes.size()) {
            this.boxes.remove(index);
            rebuildSpatialIndex();
            LabTickZones.rebuildChunkIndex(this.worldKey);
            return true;
        }
        return false;
    }

    public synchronized void clearBoxes() {
        this.boxes.clear();
        rebuildSpatialIndex();
        LabTickZones.rebuildChunkIndex(this.worldKey);
    }

    private synchronized void rebuildSpatialIndex() {
        final LongSet newChunks = new LongOpenHashSet();
        if (this.boxes.isEmpty()) {
            this.bounds = null;
            this.chunks = newChunks;
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
                    newChunks.add(ChunkPos.pack(cx, cz));
                }
            }
        }
        this.bounds = union;
        this.chunks = newChunks;
    }

    public LongSet chunkPositions() {
        return this.chunks;
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

    public boolean intersects(final net.minecraft.world.phys.AABB aabb) {
        final ZoneCuboid b = this.bounds;
        if (b == null || !b.intersects(aabb)) {
            return false;
        }
        for (final ZoneCuboid box : this.boxes) {
            if (box.intersects(aabb)) {
                return true;
            }
        }
        return false;
    }

    public boolean intersectsChunk(final int chunkX, final int chunkZ) {
        return this.chunks.contains(ChunkPos.pack(chunkX, chunkZ));
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    public void setFrozen(final boolean frozen) {
        final boolean wasFrozen = this.frozen;
        this.frozen = frozen;
        if (frozen) {
            this.stepTicks = 0;
            this.ticksToRunThisFrame = 0;
            if (!wasFrozen) {
                this.realignTicksToWorldTime();
            }
        }
    }

    public void realignTicksToWorldTime() {
        if (this.zoneGameTime > 0L) {
            final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server != null) {
                for (final ServerLevel sl : server.getAllLevels()) {
                    if (LabTickZones.resolveWorldKey(sl).equalsIgnoreCase(this.worldKey)) {
                        final long delta = sl.getGameTime() - this.zoneGameTime;
                        if (delta != 0L) {
                            sl.getBlockTicks().shiftZoneTicks(this, delta);
                            sl.getFluidTicks().shiftZoneTicks(this, delta);
                        }
                        break;
                    }
                }
            }
            this.zoneGameTime = -1L;
        }
    }

    public boolean isAccelerated() {
        return !this.frozen && this.tickRate > 20.01F;
    }

    public float tickRate() {
        return this.tickRate;
    }

    public void setTickRate(final float tickRate) {
        final float oldRate = this.tickRate;
        this.tickRate = Math.max(0.1F, Math.min(10000.0F, tickRate));
        if (oldRate > 20.01F && this.tickRate <= 20.01F) {
            this.realignTicksToWorldTime();
        }
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

    public long getGameTime(final ServerLevel level) {
        if (this.zoneGameTime < 0) {
            this.zoneGameTime = level.getGameTime();
        }
        return this.zoneGameTime;
    }

    /**
     * Whether this zone is currently permitted to tick in the current world frame.
     */
    public boolean shouldTickNow() {
        return this.ticksToRunThisFrame > 0;
    }

    /**
     * Called at the beginning of ServerLevel.tick() to determine if this zone ticks this frame.
     */
    public void onWorldTickStart(final boolean isSprinting) {
        if (this.frozen) {
            if (this.stepTicks > 0) {
                this.ticksToRunThisFrame = 1;
                this.stepTicks--;
            } else if (isSprinting) {
                this.ticksToRunThisFrame = 1;
            } else {
                this.ticksToRunThisFrame = 0;
            }
            return;
        }

        if (isSprinting) {
            this.ticksToRunThisFrame = 1;
            return;
        }

        if (Math.abs(this.tickRate - 20.0F) < 0.01F) {
            this.ticksToRunThisFrame = 1;
            return;
        }

        this.timeAccumulator += (double) this.tickRate / 20.0D;
        this.ticksToRunThisFrame = (int) this.timeAccumulator;
        this.timeAccumulator -= (double) this.ticksToRunThisFrame;
    }

    /**
     * Called at the end of ServerLevel.tick().
     */
    public void onWorldTickEnd(final ServerLevel level) {
        if (!this.isAccelerated()) {
            this.ticksToRunThisFrame = 0;
            return;
        }
        if (this.zoneGameTime < 0) {
            this.zoneGameTime = level.getGameTime();
        }
        for (int i = 0; i < this.ticksToRunThisFrame; i++) {
            this.runOneTick(level);
        }
    }

    public void runOneTick(final ServerLevel level) {
        LabTickZones.setCurrentTickingZone(this);
        this.zoneGameTime++;
        final long currentTick = this.zoneGameTime;
        try {
            level.setHandlingTick(true);
            try {
                level.runZoneTicks(this, currentTick);
                level.runZoneBlockEvents(this);
            } finally {
                level.setHandlingTick(false);
            }
            level.runZoneEntities(this);
            level.runZoneBlockEntities(this);
        } finally {
            LabTickZones.setCurrentTickingZone(null);
        }
    }
}
