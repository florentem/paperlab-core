package io.papermc.paper.lab.zone;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/**
 * Registry and dispatch coordinator for Tick Zones.
 * Completely dormant by default until enabled by the plugin.
 */
public final class LabTickZones {

    /** Dormant by default. Enabled only when PaperLab plugin activates it. */
    public static volatile boolean enabled = false;

    /** Fast-path check: true only when at least one zone exists. */
    public static volatile boolean hasActiveZones = false;

    /** worldIdentifier -> (lowercase zoneName -> LabTickZone) */
    private static final Map<String, Map<String, LabTickZone>> ZONES = new ConcurrentHashMap<>();

    /** Spatial index: worldIdentifier -> (packedChunkPos -> List<LabTickZone>) */
    private static final Map<String, Map<Long, List<LabTickZone>>> CHUNK_INDEX = new ConcurrentHashMap<>();

    /** player UUID -> lowercase zoneName */
    private static final Map<UUID, String> PLAYER_FOCUS = new ConcurrentHashMap<>();

    /** Active zone targeted by sprint/warp, or null if global sprint */
    private static volatile String activeSprintZone = null;

    /** Zone currently executing runOneTick on the main tick thread */
    private static volatile LabTickZone currentTickingZone = null;

    public static LabTickZone getCurrentTickingZone() {
        return currentTickingZone;
    }

    public static void setCurrentTickingZone(final LabTickZone zone) {
        currentTickingZone = zone;
    }

    private LabTickZones() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(final boolean value) {
        if (!value) {
            disableAll();
        } else {
            enabled = true;
            updateActiveStatus();
        }
    }

    public static void disableAll() {
        for (final Map<String, LabTickZone> map : ZONES.values()) {
            for (final LabTickZone zone : map.values()) {
                zone.realignTicksToWorldTime();
            }
        }
        enabled = false;
        hasActiveZones = false;
        ZONES.clear();
        CHUNK_INDEX.clear();
        PLAYER_FOCUS.clear();
        activeSprintZone = null;
    }

    public static synchronized void updateActiveStatus() {
        if (!enabled) {
            hasActiveZones = false;
            return;
        }
        boolean any = false;
        for (final Map<String, LabTickZone> map : ZONES.values()) {
            if (!map.isEmpty()) {
                any = true;
                break;
            }
        }
        hasActiveZones = any;
    }

    public static String resolveWorldKey(final Level level) {
        if (level == null) {
            return "";
        }
        try {
            final org.bukkit.World bukkitWorld = level.getWorld();
            if (bukkitWorld != null) {
                final String bukkitName = bukkitWorld.getName().toLowerCase(Locale.ROOT);
                if (ZONES.containsKey(bukkitName) || CHUNK_INDEX.containsKey(bukkitName)) {
                    return bukkitName;
                }
            }
        } catch (final Throwable ignored) {
        }

        final String dimKey = level.dimension().identifier().toString().toLowerCase(Locale.ROOT);
        if (ZONES.containsKey(dimKey) || CHUNK_INDEX.containsKey(dimKey)) {
            return dimKey;
        }

        final String pathKey = level.dimension().identifier().getPath().toLowerCase(Locale.ROOT);
        if (ZONES.containsKey(pathKey) || CHUNK_INDEX.containsKey(pathKey)) {
            return pathKey;
        }

        try {
            final org.bukkit.World bukkitWorld = level.getWorld();
            if (bukkitWorld != null) {
                return bukkitWorld.getName().toLowerCase(Locale.ROOT);
            }
        } catch (final Throwable ignored) {
        }
        return dimKey;
    }

    public static void rebuildChunkIndex(final String worldKey) {
        final String normKey = worldKey.toLowerCase(Locale.ROOT);
        final Map<String, LabTickZone> worldZones = ZONES.get(normKey);
        if (worldZones == null || worldZones.isEmpty()) {
            CHUNK_INDEX.remove(normKey);
            return;
        }
        final Map<Long, List<LabTickZone>> map = new ConcurrentHashMap<>();
        for (final LabTickZone zone : worldZones.values()) {
            for (final long chunkPos : zone.chunkPositions()) {
                map.computeIfAbsent(chunkPos, k -> new CopyOnWriteArrayList<>()).add(zone);
            }
        }
        CHUNK_INDEX.put(normKey, map);
    }

    // --- Registry Operations ---

    public static LabTickZone createZone(final String worldKey, final String name, final UUID owner) {
        final String normWorld = worldKey.toLowerCase(Locale.ROOT);
        final Map<String, LabTickZone> worldZones = ZONES.computeIfAbsent(normWorld, k -> new ConcurrentHashMap<>());
        final String key = name.toLowerCase(Locale.ROOT);
        final LabTickZone zone = new LabTickZone(name, normWorld, owner);
        worldZones.put(key, zone);
        rebuildChunkIndex(normWorld);
        updateActiveStatus();
        return zone;
    }

    public static boolean removeZone(final String worldKey, final String name) {
        final String normWorld = worldKey.toLowerCase(Locale.ROOT);
        final Map<String, LabTickZone> worldZones = ZONES.get(normWorld);
        if (worldZones != null) {
            final String key = name.toLowerCase(Locale.ROOT);
            final LabTickZone removed = worldZones.remove(key);
            if (removed != null) {
                // Clear any player focus pointing to this zone
                PLAYER_FOCUS.values().removeIf(z -> z.equalsIgnoreCase(name));
                rebuildChunkIndex(normWorld);
                updateActiveStatus();
                removed.realignTicksToWorldTime();
                return true;
            }
        }
        return false;
    }

    public static LabTickZone getZone(final String worldKey, final String name) {
        final String normWorld = worldKey.toLowerCase(Locale.ROOT);
        final Map<String, LabTickZone> worldZones = ZONES.get(normWorld);
        if (worldZones != null) {
            return worldZones.get(name.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    public static LabTickZone findZone(final String name) {
        final String key = name.toLowerCase(Locale.ROOT);
        for (final Map<String, LabTickZone> worldZones : ZONES.values()) {
            final LabTickZone zone = worldZones.get(key);
            if (zone != null) {
                return zone;
            }
        }
        return null;
    }

    public static LabTickZone getZone(final String name) {
        return findZone(name);
    }

    public static Collection<LabTickZone> getZonesInWorld(final String worldKey) {
        final String normWorld = worldKey.toLowerCase(Locale.ROOT);
        final Map<String, LabTickZone> map = ZONES.get(normWorld);
        return map != null ? Collections.unmodifiableCollection(map.values()) : Collections.emptyList();
    }

    public static LabTickZone getZoneAt(final Level level, final BlockPos pos) {
        if (!enabled || !hasActiveZones || level == null || pos == null) {
            return null;
        }
        final String worldKey = resolveWorldKey(level);
        final Map<Long, List<LabTickZone>> index = CHUNK_INDEX.get(worldKey);
        if (index == null || index.isEmpty()) {
            return null;
        }
        final int cx = pos.getX() >> 4;
        final int cz = pos.getZ() >> 4;
        final List<LabTickZone> zones = index.get(ChunkPos.pack(cx, cz));
        if (zones == null || zones.isEmpty()) {
            return null;
        }
        for (final LabTickZone zone : zones) {
            if (zone.contains(pos)) {
                return zone;
            }
        }
        return null;
    }

    // --- Focus Management ---

    public static void setFocus(final UUID playerUUID, final String zoneName) {
        PLAYER_FOCUS.put(playerUUID, zoneName.toLowerCase(Locale.ROOT));
    }

    public static void clearFocus(final UUID playerUUID) {
        PLAYER_FOCUS.remove(playerUUID);
    }

    public static String getFocusedZoneName(final UUID playerUUID) {
        return PLAYER_FOCUS.get(playerUUID);
    }

    public static boolean isFocused(final CommandSourceStack source) {
        if (!enabled) {
            return false;
        }
        if (source.getEntity() instanceof final ServerPlayer player) {
            return PLAYER_FOCUS.containsKey(player.getUUID());
        }
        return false;
    }

    public static LabTickZone getFocusedZone(final CommandSourceStack source) {
        if (!enabled) {
            return null;
        }
        if (source.getEntity() instanceof final ServerPlayer player) {
            final String zoneName = PLAYER_FOCUS.get(player.getUUID());
            if (zoneName != null) {
                return findZone(zoneName);
            }
        }
        return null;
    }

    // --- Permission Checks ---

    private static boolean checkZonePermission(final CommandSourceStack source, final LabTickZone zone) {
        if (!source.isPlayer()) {
            return true;
        }
        final org.bukkit.command.CommandSender sender = source.getBukkitSender();
        if (!sender.hasPermission("paperlab.tick.zone")) {
            source.sendFailure(Component.literal("Missing permission: paperlab.tick.zone"));
            return false;
        }
        if (source.getEntity() instanceof final ServerPlayer player) {
            final UUID uuid = player.getUUID();
            if (!zone.isMember(uuid) && !sender.hasPermission("paperlab.tick.zone.admin")) {
                source.sendFailure(Component.literal("You are not a member of zone '" + zone.name() + "'"));
                return false;
            }
        }
        return true;
    }

    // --- Hooks called from Level & ServerLevel ---

    public static boolean isAcceleratedZone(final Level level, final BlockPos pos) {
        if (!enabled || !hasActiveZones || level == null || pos == null) {
            return false;
        }
        final LabTickZone zone = getZoneAt(level, pos);
        return zone != null && zone.isAccelerated();
    }

    public static boolean isAcceleratedZone(final Level level, final Entity entity) {
        if (!enabled || !hasActiveZones || level == null || entity == null) {
            return false;
        }
        if (entity instanceof Player) {
            return false;
        }
        for (final Entity passenger : entity.getIndirectPassengers()) {
            if (passenger instanceof Player) {
                return false;
            }
        }
        final LabTickZone zone = getZoneAt(level, entity.blockPosition());
        if (zone != null && zone.isAccelerated()) {
            return true;
        }
        final LabTickZone intersect = getZoneIntersecting(level, entity.getBoundingBox());
        return intersect != null && intersect.isAccelerated();
    }

    public static boolean isInActiveZone(final Level level, final BlockPos pos) {
        return isAcceleratedZone(level, pos);
    }

    public static boolean isInActiveZone(final Level level, final Entity entity) {
        return isAcceleratedZone(level, entity);
    }

    public static boolean shouldTickBlock(final ServerLevel level, final BlockPos pos, final Block type) {
        return !isBlockFrozen(level, pos);
    }

    public static boolean shouldTickFluid(final ServerLevel level, final BlockPos pos, final Fluid type) {
        return !isBlockFrozen(level, pos);
    }

    public static boolean shouldRunBlockEvent(final ServerLevel level, final BlockPos pos, final Object eventData) {
        if (!enabled || !hasActiveZones || level == null || pos == null) {
            return true;
        }
        final LabTickZone zone = getZoneAt(level, pos);
        if (zone == null) {
            return true;
        }
        if (currentTickingZone == zone) {
            return true;
        }
        if (zone.isAccelerated()) {
            return false;
        }
        return zone.shouldTickNow();
    }

    public static boolean shouldTickBlockEntity(final Level level, final BlockPos pos) {
        if (!enabled || !hasActiveZones || level == null || pos == null) {
            return true;
        }
        final LabTickZone zone = getZoneAt(level, pos);
        if (zone == null) {
            return true;
        }
        if (currentTickingZone == zone) {
            return true;
        }
        if (zone.isAccelerated()) {
            return false;
        }
        return zone.shouldTickNow();
    }

    public static boolean isBlockFrozen(final Level level, final BlockPos pos) {
        if (!enabled || !hasActiveZones || level == null || pos == null) {
            return false;
        }
        final LabTickZone zone = getZoneAt(level, pos);
        if (zone == null) {
            return false;
        }
        if (currentTickingZone == zone) {
            return false;
        }
        if (zone.isAccelerated()) {
            return false;
        }
        return !zone.shouldTickNow();
    }

    public static LabTickZone getZoneIntersecting(final Level level, final net.minecraft.world.phys.AABB aabb) {
        if (!enabled || !hasActiveZones || level == null || aabb == null) {
            return null;
        }
        final String worldKey = resolveWorldKey(level);
        final Map<Long, List<LabTickZone>> index = CHUNK_INDEX.get(worldKey);
        if (index == null || index.isEmpty()) {
            return null;
        }
        final int minCx = ((int) Math.floor(aabb.minX)) >> 4;
        final int maxCx = ((int) Math.floor(aabb.maxX)) >> 4;
        final int minCz = ((int) Math.floor(aabb.minZ)) >> 4;
        final int maxCz = ((int) Math.floor(aabb.maxZ)) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                final List<LabTickZone> zones = index.get(ChunkPos.pack(cx, cz));
                if (zones != null) {
                    for (final LabTickZone zone : zones) {
                        if (zone.intersects(aabb)) {
                            return zone;
                        }
                    }
                }
            }
        }
        return null;
    }

    public static boolean isEntityFrozen(final Level level, final Entity entity) {
        if (!enabled || !hasActiveZones || level == null || entity == null) {
            return false;
        }
        if (entity instanceof Player) {
            return false; // Players are never frozen
        }
        for (final Entity passenger : entity.getIndirectPassengers()) {
            if (passenger instanceof Player) {
                return false; // Vehicles carrying players are never frozen
            }
        }
        LabTickZone zone = getZoneAt(level, entity.blockPosition());
        if (zone == null) {
            zone = getZoneIntersecting(level, entity.getBoundingBox());
        }
        if (zone == null) {
            return false;
        }
        if (currentTickingZone == zone) {
            return false;
        }
        if (zone.isAccelerated()) {
            return false;
        }
        return !zone.shouldTickNow();
    }

    public static long getGameTimeFor(final Level level, final BlockPos pos) {
        if (!enabled || !hasActiveZones || level == null || pos == null) {
            return level != null ? level.getGameTime() : 0L;
        }
        final LabTickZone current = currentTickingZone;
        if (current != null && current.contains(pos)) {
            return current.getGameTime((ServerLevel) level);
        }
        final LabTickZone zone = getZoneAt(level, pos);
        if (zone != null && zone.isAccelerated()) {
            return zone.getGameTime((ServerLevel) level);
        }
        return level.getGameTime();
    }

    public static boolean hasSkippingZones(final Level level) {
        if (!enabled || !hasActiveZones || level == null) {
            return false;
        }
        final String worldKey = resolveWorldKey(level);
        final Map<String, LabTickZone> worldZones = ZONES.get(worldKey);
        if (worldZones == null || worldZones.isEmpty()) {
            return false;
        }
        for (final LabTickZone zone : worldZones.values()) {
            if (!zone.shouldTickNow()) {
                return true;
            }
        }
        return false;
    }

    public static LongSet getSkippingChunkPositions(final Level level) {
        final LongSet chunks = new LongOpenHashSet();
        if (!enabled || !hasActiveZones || level == null) {
            return chunks;
        }
        final String worldKey = resolveWorldKey(level);
        final Map<String, LabTickZone> worldZones = ZONES.get(worldKey);
        if (worldZones == null || worldZones.isEmpty()) {
            return chunks;
        }
        for (final LabTickZone zone : worldZones.values()) {
            if (!zone.shouldTickNow()) {
                chunks.addAll(zone.chunkPositions());
            }
        }
        return chunks;
    }

    public static void onLevelTickStart(final ServerLevel level) {
        if (!enabled || !hasActiveZones) {
            return;
        }
        final String worldKey = resolveWorldKey(level);
        final Map<String, LabTickZone> worldZones = ZONES.get(worldKey);
        if (worldZones != null && !worldZones.isEmpty()) {
            final boolean serverSprinting = level.tickRateManager().isSprinting();
            if (!serverSprinting) {
                activeSprintZone = null;
            }
            for (final LabTickZone zone : worldZones.values()) {
                final boolean zoneSprinting = serverSprinting && (activeSprintZone == null ? !zone.isFrozen() : activeSprintZone.equalsIgnoreCase(zone.name()));
                zone.onWorldTickStart(zoneSprinting);
            }
        }
    }

    public static void onLevelTick(final ServerLevel level) {
        if (!enabled || !hasActiveZones) {
            return;
        }
        final String worldKey = resolveWorldKey(level);
        final Map<String, LabTickZone> worldZones = ZONES.get(worldKey);
        if (worldZones != null && !worldZones.isEmpty()) {
            for (final LabTickZone zone : worldZones.values()) {
                zone.onWorldTickEnd(level);
            }
        }
    }

    // --- Redirection handlers for /tick commands while focused ---

    public static int handleFreeze(final CommandSourceStack source, final boolean freeze) {
        final LabTickZone zone = getFocusedZone(source);
        if (zone == null) {
            source.sendFailure(Component.literal("Focused zone not found"));
            return 0;
        }
        if (!checkZonePermission(source, zone)) {
            return 0;
        }
        zone.setFrozen(freeze);
        source.sendSuccess(() -> Component.literal("[Zone " + zone.name() + "] " + (freeze ? "frozen" : "running"))
            .withStyle(freeze ? ChatFormatting.AQUA : ChatFormatting.GREEN), true);
        return freeze ? 1 : 0;
    }

    public static int handleToggle(final CommandSourceStack source) {
        final LabTickZone zone = getFocusedZone(source);
        if (zone == null) {
            source.sendFailure(Component.literal("Focused zone not found"));
            return 0;
        }
        if (!checkZonePermission(source, zone)) {
            return 0;
        }
        final boolean freeze = !zone.isFrozen();
        zone.setFrozen(freeze);
        source.sendSuccess(() -> Component.literal("[Zone " + zone.name() + "] " + (freeze ? "frozen" : "running"))
            .withStyle(freeze ? ChatFormatting.AQUA : ChatFormatting.GREEN), true);
        return 1;
    }

    public static int handleRate(final CommandSourceStack source, final float rate) {
        final LabTickZone zone = getFocusedZone(source);
        if (zone == null) {
            source.sendFailure(Component.literal("Focused zone not found"));
            return 0;
        }
        if (!checkZonePermission(source, zone)) {
            return 0;
        }
        zone.setTickRate(rate);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "[Zone %s] rate set to %.1f", zone.name(), rate))
            .withStyle(ChatFormatting.AQUA), true);
        return (int) rate;
    }

    public static int handleStep(final CommandSourceStack source, final int ticks) {
        final LabTickZone zone = getFocusedZone(source);
        if (zone == null) {
            source.sendFailure(Component.literal("Focused zone not found"));
            return 0;
        }
        if (!checkZonePermission(source, zone)) {
            return 0;
        }
        if (!zone.isFrozen()) {
            source.sendFailure(Component.literal("[Zone " + zone.name() + "] Zone is not frozen"));
            return 0;
        }
        zone.step(ticks);
        source.sendSuccess(() -> Component.literal("[Zone " + zone.name() + "] Stepping " + ticks + " ticks")
            .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    public static int handleStopStepping(final CommandSourceStack source) {
        final LabTickZone zone = getFocusedZone(source);
        if (zone == null) {
            source.sendFailure(Component.literal("Focused zone not found"));
            return 0;
        }
        if (!checkZonePermission(source, zone)) {
            return 0;
        }
        zone.stopStepping();
        source.sendSuccess(() -> Component.literal("[Zone " + zone.name() + "] Stepping stopped")
            .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    public static int handleSprint(final CommandSourceStack source, final int time) {
        final LabTickZone zone = getFocusedZone(source);
        if (zone == null) {
            source.sendFailure(Component.literal("Focused zone not found"));
            return 0;
        }
        if (!checkZonePermission(source, zone)) {
            return 0;
        }
        activeSprintZone = zone.name();
        final ServerTickRateManager manager = io.papermc.paper.lab.tick.LabPerWorldTick.getManager(source);
        manager.requestGameToSprint(time);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "[Zone %s] sprint %dt", zone.name(), time))
            .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    public static int handleStopSprinting(final CommandSourceStack source) {
        final LabTickZone zone = getFocusedZone(source);
        if (zone == null) {
            source.sendFailure(Component.literal("Focused zone not found"));
            return 0;
        }
        if (!checkZonePermission(source, zone)) {
            return 0;
        }
        activeSprintZone = null;
        final ServerTickRateManager manager = io.papermc.paper.lab.tick.LabPerWorldTick.getManager(source);
        final boolean success = manager.stopSprinting();
        if (success) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "[Zone %s] sprint stopped", zone.name()))
                .withStyle(ChatFormatting.YELLOW), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Not sprinting/warping"));
            return 0;
        }
    }

    public static int handleQuery(final CommandSourceStack source) {
        final LabTickZone zone = getFocusedZone(source);
        if (zone == null) {
            source.sendFailure(Component.literal("Focused zone not found"));
            return 0;
        }
        if (source.isPlayer() && !source.getBukkitSender().hasPermission("paperlab.tick.zone")) {
            source.sendFailure(Component.literal("Missing permission: paperlab.tick.zone"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "[Zone %s] Status: %s | Rate: %.1f | Boxes: %d | Time: %d",
            zone.name(),
            zone.isFrozen() ? (zone.stepTicks() > 0 ? "stepping" : "frozen") : "running",
            zone.tickRate(),
            zone.boxes().size(),
            zone.zoneGameTime()
        )).withStyle(ChatFormatting.AQUA), false);
        return (int) zone.tickRate();
    }
}
