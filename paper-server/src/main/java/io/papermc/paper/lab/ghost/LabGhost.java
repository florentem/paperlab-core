package io.papermc.paper.lab.ghost;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Observer mode: the player stops affecting the simulation but keeps interacting with
 * the world.
 *
 * <p>The point is to fly along a chunk border and take a farm apart while a bot keeps it
 * running, without distorting what you are measuring: load no chunks, take no mobcap,
 * do not move the spawn boundary, do not wake mobs and do not interfere with despawning.
 *
 * <p><b>What the mode does NOT do.</b> This is not spectator: blocks place and break,
 * containers open, the inventory works. Only the effect on the server simulation is off.
 *
 * <h2>What it is made of</h2>
 * <ol>
 *   <li><b>Chunks stop ticking</b> — the personal simulation distance is set to {@code 0}
 *       and {@code ChunkMap.skipPlayer} returns {@code true}.
 *       <p>{@code skipPlayer} alone is <b>not enough</b>, and that was a real mistake: it
 *       only silences the legacy {@code DistanceManager}, while the actual loading in
 *       Paper is done by {@code moonrise$getPlayerChunkLoader()}, which
 *       {@code updatePlayerStatus} calls <i>outside</i> the {@code ignored} check. So the
 *       observer kept making the chunks around them entity-ticking, and nearby mobs woke up.
 *       <p>With a simulation distance of zero the observer only adds FULL-level tickets.
 *       Such a ticket grants neither block-ticking nor entity-ticking, so it cannot change
 *       the bot's ticking geometry — while the world still renders.
 *       <p>Moonrise does not support a true zero: a negative value means "inherit the
 *       world's", so one entity-ticking chunk remains under the observer. That is a known
 *       limitation, not an oversight.</li>
 *   <li><b>Mobcap and spawn radius</b> — the player is skipped at four read sites: the
 *       census in {@code ChunkMap.updatePlayerMobTypeMap}, backoff accrual in
 *       {@code updateFailurePlayerMobTypeMap}, the minimum-headroom search in
 *       {@code NaturalSpawner.spawnForChunk}, and chunk selection in
 *       {@code isChunkNearPlayer}.
 *       <p><b>Removing the player from {@code NearbyPlayers} is not an option</b>, even
 *       though it looks like one site instead of four. That map is driven by the entity
 *       lifecycle: {@code tickPlayer} throws
 *       {@code IllegalStateException: Don't have player} on every move, and entity
 *       tracking breaks with it — the player stops seeing everyone else. Verified on a
 *       live server.</li>
 *   <li><b>Despawning, spawn position, trial spawner</b> — through Paper's own
 *       {@code Player.affectsSpawning} flag. No patch needed: it is already honoured by
 *       {@code EntitySelector.PLAYER_AFFECTS_SPAWNING} and
 *       {@code EntityGetter.getNearestPlayerAffectingSpawning}.</li>
 *   <li><b>EAR</b> — the player takes no part in building activation ranges; otherwise
 *       they would wake the mobs around them.</li>
 *   <li><b>Mobs do not notice</b> — {@code LivingEntity.canBeSeenByAnyone} returns
 *       {@code false}. That is the single entry point into
 *       {@code TargetingConditions.test}, so no AI selector will pick such a player.</li>
 * </ol>
 *
 * <p>The state is held in memory and cleared on restart: this is a debugging mode, not a
 * property of the player, and it should not survive one.
 */
public final class LabGhost {

    private static final Set<UUID> GHOSTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private LabGhost() {
    }

    public static boolean isGhost(final Entity entity) {
        return !GHOSTS.isEmpty()
            && entity instanceof ServerPlayer
            && GHOSTS.contains(entity.getUUID());
    }

    public static boolean isGhost(final UUID uuid) {
        return GHOSTS.contains(uuid);
    }

    public static boolean any() {
        return !GHOSTS.isEmpty();
    }

    /**
     * Toggles the mode.
     *
     * @return {@code true} if the mode is now on
     */
    public static boolean toggle(final ServerPlayer player) {
        return set(player, !GHOSTS.contains(player.getUUID()));
    }

    public static boolean set(final ServerPlayer player, final boolean ghost) {
        final ServerLevel level = player.level();
        if (ghost) {
            GHOSTS.add(player.getUUID());
        } else {
            GHOSTS.remove(player.getUUID());
        }

        // Paper's own flag: despawning, spawn position choice, trial spawner.
        player.affectsSpawning = !ghost;

        // Ticking chunks: 0 is the minimum Moonrise supports.
        // -1 returns the player to the world value.
        io.papermc.paper.FeatureHooks.setSimulationDistance(player, ghost ? 0 : -1);

        // Recompute participation in chunk loading.
        //
        // MEASURED: turning the mode on is NOT instant. sim=0 applies immediately, but
        // Moonrise releases already-issued ticking tickets lazily and rate-limited — on
        // the bench it settles from 121 chunks to 1 in about 30 seconds. Turning it off,
        // by contrast, takes seconds.
        //
        // Forcibly re-registering the player in the chunk loader
        // (removePlayerFromDistanceMaps + addPlayerToDistanceMaps) was tried and does NOT
        // remove the delay: the ticket release queue still drains gradually. So there is
        // no extra churn logic here — after enabling you simply wait.
        level.getChunkSource().chunkMap.move(player);

        // Hide from other players: convenient for an observer, no effect on simulation.
        player.setInvisible(ghost);
        return ghost;
    }

    /** Clear the mode on quit, otherwise the state would survive a reconnect. */
    public static void onDisconnect(final ServerPlayer player) {
        if (GHOSTS.remove(player.getUUID())) {
            player.affectsSpawning = true;
        }
    }

    public static int count() {
        return GHOSTS.size();
    }
}
