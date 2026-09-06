package io.papermc.paper.lab.dump;

import io.papermc.paper.lab.zone.LabTickZone;
import io.papermc.paper.lab.zone.LabTickZones;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

/**
 * High-resolution event dumper for redstone mechanics and tick zone debugging.
 * Records scheduled ticks, block events, block changes, entity updates, and piston movements
 * with strict microtiming order and call stack depth tracking.
 */
public final class ZoneDumpManager {

    public static volatile boolean active = false;
    private static volatile Session currentSession = null;
    private static final Object LOCK = new Object();
    private static final ThreadLocal<Integer> CALL_DEPTH = ThreadLocal.withInitial(() -> 0);

    public static int currentDepth() {
        return CALL_DEPTH.get();
    }

    public static void pushDepth() {
        CALL_DEPTH.set(CALL_DEPTH.get() + 1);
    }

    public static void popDepth() {
        CALL_DEPTH.set(Math.max(0, CALL_DEPTH.get() - 1));
    }

    public static final class Session {
        public final String name;
        public final String worldKey;
        public final Predicate<BlockPos> posFilter;
        public final int maxTicks;
        public final Path filePath;
        private final PrintWriter writer;

        private int ticksRecorded = 0;
        private int totalEventsRecorded = 0;
        private int eventsThisTick = 0;
        private boolean inZoneSubTick = false;
        private int subTickIndex = 0;
        private LabTickZone currentZone = null;
        private volatile String currentPhase = "TICK_START";

        public Session(final String name, final String worldKey, final Predicate<BlockPos> posFilter,
                       final int maxTicks, final Path filePath, final PrintWriter writer) {
            this.name = name;
            this.worldKey = worldKey;
            this.posFilter = posFilter;
            this.maxTicks = maxTicks;
            this.filePath = filePath;
            this.writer = writer;
        }

        public boolean matches(final Level level, final BlockPos pos) {
            if (level == null || pos == null) {
                return false;
            }
            if (!this.worldKey.isEmpty() && !LabTickZones.resolveWorldKey(level).equalsIgnoreCase(this.worldKey)) {
                return false;
            }
            return this.posFilter.test(pos);
        }

        public void setPhase(final String phase) {
            this.currentPhase = phase;
        }

        public synchronized void logEvent(final String eventType, final BlockPos pos, final String details) {
            this.totalEventsRecorded++;
            this.eventsThisTick++;
            final int depth = CALL_DEPTH.get();
            final StringBuilder sb = new StringBuilder();
            if (this.inZoneSubTick && this.currentZone != null) {
                sb.append(String.format(Locale.ROOT, "  #%04d [SubTick %d | %s]", this.eventsThisTick, this.subTickIndex, eventType));
            } else {
                sb.append(String.format(Locale.ROOT, "  #%04d [%s | %s]", this.eventsThisTick, this.currentPhase, eventType));
            }
            if (depth > 0) {
                sb.append(" ");
                for (int i = 0; i < Math.min(depth, 8); i++) {
                    sb.append("-> ");
                }
            } else {
                sb.append(" ");
            }
            sb.append(details);
            this.writer.println(sb.toString());
        }

        public synchronized void onTickStart(final ServerLevel level) {
            this.ticksRecorded++;
            this.eventsThisTick = 0;
            this.subTickIndex = 0;
            this.currentPhase = "START";
            this.writer.println(String.format(Locale.ROOT, "=== TICK %d | Dim: %s ===", level.getGameTime(), LabTickZones.resolveWorldKey(level)));
        }

        public synchronized void onTickEnd(final ServerLevel level) {
            this.currentPhase = "END";
            this.writer.flush();
            if (this.ticksRecorded >= this.maxTicks) {
                ZoneDumpManager.stopDump();
            }
        }

        public synchronized void onZoneSubTickStart(final LabTickZone zone) {
            this.inZoneSubTick = true;
            this.currentZone = zone;
            this.subTickIndex++;
            this.currentPhase = "ZONE_SUBTICK";
            this.writer.println(String.format(Locale.ROOT, "--- SUB-TICK %d (Zone: %s, ZoneTick: %d) ---",
                this.subTickIndex, zone.name(), zone.zoneGameTime()));
        }

        public synchronized void onZoneSubTickEnd(final LabTickZone zone) {
            this.inZoneSubTick = false;
            this.currentZone = null;
            this.writer.flush();
        }

        public synchronized void close() {
            this.writer.println("# -------------------------------------------------------------");
            this.writer.println(String.format(Locale.ROOT, "# Dump Complete: %d ticks, %d events", this.ticksRecorded, this.totalEventsRecorded));
            this.writer.flush();
            this.writer.close();
        }

        public int getTicksRecorded() {
            return this.ticksRecorded;
        }

        public int getTotalEventsRecorded() {
            return this.totalEventsRecorded;
        }
    }

    private ZoneDumpManager() {
    }

    public static boolean isDumping() {
        return active;
    }

    public static String getStatus() {
        final Session session = currentSession;
        if (session == null || !active) {
            return "No active dump session.";
        }
        return String.format(Locale.ROOT, "Dump '%s' in progress: %d/%d ticks, %d events -> %s",
            session.name, session.getTicksRecorded(), session.maxTicks, session.getTotalEventsRecorded(), session.filePath);
    }

    public static String startZoneDump(final LabTickZone zone, final int maxTicks) {
        return startDump(zone.name(), zone.worldKey(), zone::contains, maxTicks);
    }

    public static String startAreaDump(final String worldKey, final BlockPos min, final BlockPos max, final int maxTicks) {
        final int minX = Math.min(min.getX(), max.getX());
        final int minY = Math.min(min.getY(), max.getY());
        final int minZ = Math.min(min.getZ(), max.getZ());
        final int maxX = Math.max(min.getX(), max.getX());
        final int maxY = Math.max(min.getY(), max.getY());
        final int maxZ = Math.max(min.getZ(), max.getZ());

        final String name = String.format(Locale.ROOT, "area_%d_%d_%d_to_%d_%d_%d", minX, minY, minZ, maxX, maxY, maxZ);
        final Predicate<BlockPos> filter = pos ->
            pos.getX() >= minX && pos.getX() <= maxX
            && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;

        return startDump(name, worldKey, filter, maxTicks);
    }

    public static String startDump(final String name, final String worldKey, final Predicate<BlockPos> filter, final int maxTicks) {
        synchronized (LOCK) {
            if (active && currentSession != null) {
                return "A dump session is already running: " + currentSession.name;
            }

            try {
                final File dumpsDir = new File("dumps");
                if (!dumpsDir.exists()) {
                    dumpsDir.mkdirs();
                }

                final String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                final File file = new File(dumpsDir, "dump_" + name + "_" + timeStamp + ".log");
                final PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file, false)));

                pw.println("# PaperLab Event Dump");
                pw.println("# Target: " + name);
                pw.println("# World: " + worldKey);
                pw.println("# Max Ticks: " + maxTicks);
                pw.println("# Started: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()));
                pw.println("# Format: #<seq> [<phase> | <type>] [-> depth] <details>");
                pw.println("# -------------------------------------------------------------");
                pw.flush();

                currentSession = new Session(name, worldKey, filter, maxTicks, file.toPath(), pw);
                active = true;

                return String.format(Locale.ROOT, "Started dump for '%s' (max %d ticks) -> %s", name, maxTicks, file.getPath());
            } catch (final IOException e) {
                return "Failed to start dump: " + e.getMessage();
            }
        }
    }

    public static String stopDump() {
        synchronized (LOCK) {
            if (!active || currentSession == null) {
                return "No active dump session to stop.";
            }

            final Session session = currentSession;
            active = false;
            currentSession = null;
            session.close();

            return String.format(Locale.ROOT, "Stopped dump for '%s'. Recorded %d ticks, %d events -> %s",
                session.name, session.getTicksRecorded(), session.getTotalEventsRecorded(), session.filePath);
        }
    }

    // --- Phase and Lifecycle Hooks ---

    public static void setPhase(final String phase) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null) {
            session.setPhase(phase);
        }
    }

    public static void onTickStart(final ServerLevel level) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && (session.worldKey.isEmpty() || session.worldKey.equalsIgnoreCase(LabTickZones.resolveWorldKey(level)))) {
            session.onTickStart(level);
        }
    }

    public static void onTickEnd(final ServerLevel level) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && (session.worldKey.isEmpty() || session.worldKey.equalsIgnoreCase(LabTickZones.resolveWorldKey(level)))) {
            session.onTickEnd(level);
        }
    }

    public static void onZoneSubTickStart(final LabTickZone zone) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && session.name.equalsIgnoreCase(zone.name())) {
            session.onZoneSubTickStart(zone);
        }
    }

    public static void onZoneSubTickEnd(final LabTickZone zone) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && session.name.equalsIgnoreCase(zone.name())) {
            session.onZoneSubTickEnd(zone);
        }
    }

    public static void onBlockTick(final Level level, final BlockPos pos, final Block block) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && session.matches(level, pos)) {
            session.logEvent("BLOCK_TICK", pos, BuiltInRegistries.BLOCK.getKey(block) + " at " + pos.toShortString());
        }
    }

    public static void onFluidTick(final Level level, final BlockPos pos, final Fluid fluid) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && session.matches(level, pos)) {
            session.logEvent("FLUID_TICK", pos, BuiltInRegistries.FLUID.getKey(fluid) + " at " + pos.toShortString());
        }
    }

    public static void onBlockEvent(final Level level, final BlockPos pos, final Block block, final int type, final int data) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && session.matches(level, pos)) {
            final String action = (block instanceof PistonBaseBlock)
                ? (type == 0 ? "EXTEND" : "RETRACT")
                : ("type=" + type + " data=" + data);
            session.logEvent("BLOCK_EVENT", pos, BuiltInRegistries.BLOCK.getKey(block) + " " + action + " at " + pos.toShortString());
        }
    }

    public static void onBlockStateChange(final Level level, final BlockPos pos, final BlockState oldState, final BlockState newState) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && session.matches(level, pos)) {
            session.logEvent("BLOCK_CHANGE", pos, oldState + " -> " + newState + " at " + pos.toShortString());
        }
    }

    public static void onPistonMovingTick(final Level level, final BlockPos pos, final float progress,
                                          final boolean extending, final BlockState movedState) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && session.matches(level, pos)) {
            session.logEvent("PISTON_TICK", pos, String.format(Locale.ROOT,
                "progress=%.2f extending=%b moved=%s at %s",
                progress, extending, movedState, pos.toShortString()));
        }
    }

    public static void onEntitySpawn(final Level level, final Entity entity) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && session.matches(level, entity.blockPosition())) {
            session.logEvent("ENTITY_SPAWN", entity.blockPosition(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " id=" + entity.getId() + " pos=" + entity.position());
        }
    }

    public static void onEntityTick(final Level level, final Entity entity) {
        if (!active) return;
        final Session session = currentSession;
        if (session != null && session.matches(level, entity.blockPosition())) {
            String details = "";
            if (entity instanceof PrimedTnt tnt) {
                details = " fuse=" + tnt.getFuse() + " vel=" + tnt.getDeltaMovement();
            } else if (entity instanceof FallingBlockEntity fb) {
                details = " block=" + fb.getBlockState() + " time=" + fb.time;
            }
            session.logEvent("ENTITY_TICK", entity.blockPosition(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " id=" + entity.getId() + " pos=" + entity.position() + details);
        }
    }

    public static void onExplosion(final Level level, final double x, final double y, final double z,
                                   final float power, final List<BlockPos> blocks) {
        if (!active) return;
        final Session session = currentSession;
        final BlockPos center = BlockPos.containing(x, y, z);
        if (session != null && (session.matches(level, center) || (blocks != null && blocks.stream().anyMatch(p -> session.matches(level, p))))) {
            session.logEvent("EXPLOSION", center, String.format(Locale.ROOT,
                "center=(%.2f, %.2f, %.2f) power=%.2f destroyedBlocks=%d",
                x, y, z, power, blocks != null ? blocks.size() : 0));
        }
    }
}
