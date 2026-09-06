package io.papermc.paper.lab.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * AFK bot: a real {@link ServerPlayer} without a client.
 *
 * <p><b>One canonical tick.</b> This class deliberately does <i>not</i> override
 * {@code tick()}. The reasoning, confirmed against the code:
 *
 * <ul>
 *   <li>players are added to {@code ServerLevel.entityTickList} (entity callbacks,
 *       {@code ServerLevel.java:2841}), so {@code ServerPlayer.tick()} is invoked by the
 *       level's ordinary entity tick — exactly as for a live player;</li>
 *   <li>for a live player {@code ServerPlayer.doTick()} is called from
 *       {@code ServerGamePacketListenerImpl.tick()} ({@code :387}), which is ticked by
 *       {@code Connection.tick()} from {@code ServerConnectionListener.tick()};</li>
 *   <li>inside {@code MinecraftServer.tickChildren} the phase order is: {@code levels}
 *       (level and entity ticking), then {@code connection} ({@code tickConnection()},
 *       {@code MinecraftServer.java:1838}), then {@code players}.</li>
 * </ul>
 *
 * So within one tick a live player sees <b>{@code tick()} and then {@code doTick()}</b>,
 * in different phases. Hence {@link LabBotRegistry} calls {@code doTick()} from a hook
 * placed right after {@code tickConnection()}, while {@code tick()} arrives from the level
 * on its own. That way both the phase and the order match.
 *
 * <p>Fabric Carpet reaches the same relative order differently: it overrides {@code tick()}
 * and calls {@code super.tick()} then {@code doTick()} inside. The order comes out right,
 * but both calls land in the entity phase, before the connection phase. The hook was
 * chosen here so that the phase matches too.
 *
 * <p><b>What is NOT proven:</b> that a bot is equivalent to a live player. Matching call
 * order is a necessary condition, not a proof. Separate checks are still needed for at
 * least: lifecycle (spawn/remove/death/respawn/restart), chunk tickets, presence in
 * {@code NearbyPlayers} and the local mobcap, EAR immunity nearby, and combat actions.
 * Until those pass, a bot is a load instrument, not a reference player.
 */
public final class LabBot extends ServerPlayer {

    private final String labName;
    private final LabActionPack actions = new LabActionPack(this);

    /**
     * How the bot was created. Needed to bring it back after death: a live player's
     * respawn is sent by their client, and a bot has nobody to send it.
     */
    private LabBotRegistry.Spec spec;

    /** Whether to respawn the bot after death. Off by default, as before. */
    private boolean autoRespawn;

    LabBot(final MinecraftServer server,
           final ServerLevel level,
           final GameProfile profile,
           final ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
        this.labName = profile.name();
    }

    public String labName() {
        return this.labName;
    }

    public LabActionPack actions() {
        return this.actions;
    }

    LabBotRegistry.Spec spec() {
        return this.spec;
    }

    void spec(final LabBotRegistry.Spec value) {
        this.spec = value;
    }

    public boolean autoRespawn() {
        return this.autoRespawn;
    }

    public void autoRespawn(final boolean value) {
        this.autoRespawn = value;
    }

    /**
     * Called by {@link LabBotRegistry} in the connection phase — where a live player's
     * packet listener does it.
     */
    void tickConnectionPhase() {
        // Actions run before doTick(): in this phase a live player's incoming packets are
        // processed first, and only then is doTick() called.
        this.actions.tick();
        this.doTick();
    }
}
