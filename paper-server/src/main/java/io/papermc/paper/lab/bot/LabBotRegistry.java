package io.papermc.paper.lab.bot;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.util.Util;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Registry of AFK bots and their tick in the correct phase.
 *
 * <p>Every operation must run on the main server thread, except the name resolution
 * explicitly marked as off-thread.
 */
public final class LabBotRegistry {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("PaperLab");

    /** Name to bot. Insertion order is kept so that ticking is deterministic. */
    private static final Map<String, LabBot> BOTS = new LinkedHashMap<>();

    /**
     * How the bot was created — everything needed to bring it back.
     *
     * <p>{@code skinName} and {@code inGameName} differ when a suffix is set: the skin is
     * fetched for the former, while the UUID and tab-list slot come from the latter.
     */
    public record Spec(String skinName, String inGameName, ResourceKey<Level> dimension,
                       Vec3 pos, float yaw, float pitch, GameType gameMode, boolean flying) {
    }

    /** A bot waiting to be respawned: its spec and how many ticks are left. */
    private record Pending(Spec spec, boolean autoRespawn, int ticksLeft) {
    }

    /**
     * Delay before respawning. The second is not cosmetic: death drops items and fires an
     * event, and respawning a player in the same tick is a reliable way to reach a state
     * the engine does not expect.
     */
    private static final int RESPAWN_DELAY_TICKS = 20;

    private static final List<Pending> PENDING = new ArrayList<>();

    /**
     * Names whose profile is already being resolved. Resolution goes over the network and
     * comes back a few ticks later; without this marker two quick {@code spawn} calls
     * would create two bots with the same name.
     */
    private static final Set<String> SPAWNING = new HashSet<>();

    private LabBotRegistry() {
    }

    /**
     * Called by a hook in {@code MinecraftServer} right after {@code tickConnection()}.
     *
     * <p>This is the only place a bot gets {@code doTick()} — exactly the phase
     * ({@code tickChildren}: levels -&gt; <b>connection</b> -&gt; players) and exactly the
     * order relative to {@code tick()} that a live player sees. Without the hook a bot
     * would get {@code tick()} from the level but not {@code doTick()}, so its hunger and
     * respawn timers, among others, would never advance.
     */
    public static void tickConnectionPhase() {
        if (!io.papermc.paper.lab.rules.LabRuleState.playerCommandEnabled && BOTS.isEmpty() && PENDING.isEmpty()) {
            return;
        }
        tickPending();
        if (BOTS.isEmpty()) {
            return;
        }
        // Copy: doTick() may remove the bot (death, kick).
        for (final LabBot bot : new ArrayList<>(BOTS.values())) {
            // Nobody respawns a dead bot: it has no client to send
            // ServerboundClientCommandPacket. Left alone it lingers as a ghost in the tab
            // list, so we remove it through the normal path.
            if (bot.isDeadOrDying() || bot.isRemoved() || bot.hasDisconnected()) {
                // Take the spec before removal: after remove the bot remembers nothing.
                final Spec spec = bot.spec();
                final boolean respawn = bot.autoRespawn() && bot.isDeadOrDying();
                remove(bot.labName());
                if (respawn && spec != null) {
                    PENDING.add(new Pending(spec, true, RESPAWN_DELAY_TICKS));
                    LOGGER.info("Bot {} died, respawning in {} ticks",
                        spec.inGameName(), RESPAWN_DELAY_TICKS);
                }
                continue;
            }
            try {
                bot.tickConnectionPhase();
            } catch (final Throwable t) {
                LOGGER.error("Bot {} failed to tick, removing it", bot.labName(), t);
                remove(bot.labName());
            }
        }
    }

    /**
     * Creates a bot and registers it as an ordinary player.
     *
     * <p><b>Creation is asynchronous.</b> The profile — and with it the skin and cape — is
     * resolved through Mojang services, so the bot appears a few ticks after this method
     * returns. Only what is visible immediately is checked synchronously; everything else
     * is reported through {@code feedback}, which is invoked on the main thread.
     *
     * <p><b>On real names.</b> If the name belongs to an existing player, the bot takes
     * their UUID and their skin. The price: while such a bot is online that player cannot
     * log in — the server sees a duplicate login. The behaviour is deliberate and matches
     * Carpet.
     *
     * @return an error message if the problem is visible immediately, otherwise {@code null}
     */
    public static @Nullable String spawn(final MinecraftServer server,
                                         final String name,
                                         final ServerLevel level,
                                         final Vec3 pos,
                                         final float yaw,
                                         final float pitch,
                                         final GameType gameMode,
                                         final boolean flying,
                                         final boolean autoRespawn,
                                         final Consumer<Component> feedback) {
        if (name.isBlank()) {
            return "bot name cannot be empty";
        }
        // The in-game name carries the suffix, the skin is fetched for the name without
        // it. The split is what lets the bot look like the intended player without taking
        // their UUID — otherwise that player could not log in, the server would see a
        // duplicate login.
        final String suffix = io.papermc.paper.lab.rules.LabRuleState.fakePlayerNameSuffix;
        final String skinName = name;
        final String inGameName = suffix.isEmpty() ? name : name + suffix;
        if (inGameName.length() > 16) {
            return "name with suffix is longer than 16 chars: '" + inGameName + "'";
        }
        final String key = inGameName.toLowerCase(Locale.ROOT);
        if (BOTS.containsKey(key)) {
            return "bot '" + inGameName + "' already exists";
        }
        if (server.getPlayerList().getPlayerByName(inGameName) != null) {
            return "player '" + inGameName + "' is already online";
        }
        if (!SPAWNING.add(key)) {
            return "bot '" + inGameName + "' is already being created";
        }

        CompletableFuture
            .supplyAsync(() -> identity(server, skinName), Util.backgroundExecutor())
            .thenCompose(identity -> ResolvableProfile.createUnresolved(identity.id())
                .resolveProfile(server.services().profileResolver())
                // There may be no profile at all: an invented name, or a server with no
                // network. Not an error — the bot simply stays skinless.
                .exceptionally(t -> identity.toUncompletedGameProfile())
                .thenApply(resolved -> resolved.name().isEmpty()
                    ? identity.toUncompletedGameProfile() : resolved))
            .whenCompleteAsync((profile, error) -> {
                SPAWNING.remove(key);
                if (error != null) {
                    LOGGER.error("Could not resolve the profile for bot {}", inGameName, error);
                    feedback.accept(Component.literal("could not resolve profile: " + error));
                    return;
                }
                // Everything below runs inside a future callback. An exception here is
                // swallowed silently by CompletableFuture: nothing in the log, nothing in
                // chat, and the bot just never appears. That happened once — hence the
                // explicit catch and the loud message.
                try {
                    // Skins live in the profile properties and do not depend on the
                    // name; the UUID does. We derive it from the suffixed name, otherwise
                    // the clash with a live player remains.
                    final GameProfile inGame = withName(profile, inGameName, suffix);
                    final Spec spec = new Spec(skinName, inGameName, level.dimension(),
                        pos, yaw, pitch, gameMode, flying);
                    final String failure = create(server, inGame, level, spec, autoRespawn);
                    if (failure != null) {
                        feedback.accept(Component.literal(failure));
                    }
                } catch (final Throwable t) {
                    LOGGER.error("Could not create bot {}", inGameName, t);
                    feedback.accept(Component.literal("could not create bot: " + t));
                }
            }, server);
        return null;
    }

    /**
     * A profile under the in-game name: properties (skin, cape) are kept, while the name
     * and UUID come from the suffixed name.
     *
     * <p>With no suffix the profile is left as is — the bot then really does take the
     * player's UUID, and that is a deliberate mode: an empty rule value means the user
     * asked for exactly that.
     */
    private static GameProfile withName(final GameProfile profile,
                                        final String inGameName,
                                        final String suffix) {
        if (suffix.isEmpty()) {
            return profile;
        }
        // Carry the properties (textures included) through the constructor rather than
        // putAll: GameProfile's property map may be immutable, and putAll then throws.
        return new GameProfile(
            UUIDUtil.createOfflinePlayerUUID(inGameName), inGameName, profile.properties());
    }

    /** Respawn the bots whose delay has elapsed. */
    private static void tickPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        final List<Pending> ready = new ArrayList<>();
        PENDING.replaceAll(pending -> new Pending(pending.spec(), pending.autoRespawn(),
            pending.ticksLeft() - 1));
        PENDING.removeIf(pending -> {
            if (pending.ticksLeft() > 0) {
                return false;
            }
            ready.add(pending);
            return true;
        });

        for (final Pending pending : ready) {
            final Spec spec = pending.spec();
            final MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            final ServerLevel level = server == null ? null : server.getLevel(spec.dimension());
            if (level == null) {
                LOGGER.warn("Cannot respawn bot {}: dimension {} is gone",
                    spec.inGameName(), spec.dimension().identifier());
                continue;
            }
            final String error = spawn(server, spec.skinName(), level, spec.pos(),
                spec.yaw(), spec.pitch(), spec.gameMode(), spec.flying(), pending.autoRespawn(),
                message -> LOGGER.warn("Respawn of {} failed: {}",
                    spec.inGameName(), message.getString()));
            if (error != null) {
                LOGGER.warn("Respawn of {} failed: {}", spec.inGameName(), error);
            }
        }
    }

    /**
     * Who this name belongs to. The server's name cache is asked first: for an existing
     * player it returns the real UUID and the bot gets their skin. An invented name keeps
     * an offline UUID, as before.
     *
     * <p>Runs off the main thread: name resolution goes over the network.
     */
    private static NameAndId identity(final MinecraftServer server, final String name) {
        try {
            final Optional<NameAndId> found = server.services().nameToIdCache().get(name);
            if (found.isPresent()) {
                return found.get();
            }
        } catch (final Throwable t) {
            LOGGER.debug("Name {} did not resolve, using an offline UUID", name, t);
        }
        return new NameAndId(UUIDUtil.createOfflinePlayerUUID(name), name);
    }

    /** The creation itself. Main thread only. */
    private static @Nullable String create(final MinecraftServer server,
                                           final GameProfile profile,
                                           final ServerLevel level,
                                           final Spec spec,
                                           final boolean autoRespawn) {
        final Vec3 pos = spec.pos();
        final float yaw = spec.yaw();
        final float pitch = spec.pitch();
        final GameType gameMode = spec.gameMode();
        final boolean flying = spec.flying();
        final String key = profile.name().toLowerCase(Locale.ROOT);
        if (BOTS.containsKey(key)) {
            return "bot '" + profile.name() + "' already exists";
        }
        if (server.getPlayerList().getPlayer(profile.id()) != null) {
            return "a player with that UUID is already online";
        }

        final LabBot bot = new LabBot(server, level, profile, ClientInformation.createDefault());
        final LabBotConnection connection = new LabBotConnection();

        server.getPlayerList().placeNewPlayer(
            connection, bot, CommonListenerCookie.createInitial(profile, false));

        // Player data has to be loaded explicitly. A normal login reads it before
        // placeNewPlayer, but we construct the bot ourselves, bypassing login, so without
        // this call inventory, health and experience are not restored between spawn and
        // kill. Saving in PlayerList.remove worked all along — only loading was missing.
        loadSavedData(server, bot);

        // The bot may have been saved while riding; otherwise it reappears on the old vehicle.
        bot.stopRiding();

        // placeNewPlayer puts the player at the saved or world-spawn coordinates. We move
        // them to the requested point after registration so that chunk tickets and
        // NearbyPlayers are recomputed through the usual path.
        bot.teleportTo(level, pos.x, pos.y, pos.z, java.util.Set.of(), yaw, pitch, true);
        bot.setYHeadRot(yaw);
        if (bot.getHealth() <= 0.0F) {
            bot.setHealth(bot.getMaxHealth());
        }
        // Through ServerPlayer#setGameMode rather than gameMode.changeGameModeForPlayer:
        // the latter bypasses PlayerGameModeChangeEvent, and Paper rightly catches that
        // with scanJarForBadCalls. A bot is an ordinary player; plugins deserve to know.
        bot.setGameMode(gameMode);
        bot.getAbilities().flying = flying;
        bot.onUpdateAbilities();
        // Default step height: without it the bot can get stuck on slabs.
        final var stepHeight = bot.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(0.6D);
        }
        // All skin layers. A bot has no client to send model settings, so without this
        // line neither the second layer nor the cape shows.
        bot.getEntityData().set(Avatar.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F);

        // Broadcast rotation and position explicitly: clients that were already shown the
        // bot would otherwise see it facing the wrong way until it first moves.
        server.getPlayerList().broadcastAll(
            new ClientboundRotateHeadPacket(bot, (byte) (bot.yHeadRot * 256 / 360)), level.dimension());
        server.getPlayerList().broadcastAll(
            ClientboundEntityPositionSyncPacket.of(bot), level.dimension());

        bot.spec(spec);
        bot.autoRespawn(autoRespawn);

        BOTS.put(key, bot);
        return null;
    }

    /**
     * Reads the bot's saved data: inventory, health, experience, ender chest, and also
     * restores ender pearls and vehicles, exactly as an ordinary player login does.
     */
    private static void loadSavedData(final MinecraftServer server, final LabBot bot) {
        try (final ProblemReporter.ScopedCollector reporter =
                 new ProblemReporter.ScopedCollector(bot.problemPath(), LOGGER)) {
            server.getPlayerList().loadPlayerData(bot.nameAndId())
                .map(tag -> TagValueInput.create(reporter, bot.registryAccess(), tag))
                .ifPresent(input -> {
                    bot.load(input);
                    bot.loadAndSpawnEnderPearls(input);
                    bot.loadAndSpawnParentVehicle(input);
                });
        } catch (final Throwable t) {
            LOGGER.error("Could not read saved data for bot {}", bot.labName(), t);
        }
    }

    /**
     * Removes the bot through the normal {@code PlayerList.remove} path: data is saved,
     * chunk tickets are released, {@code NearbyPlayers} is left.
     *
     * @return {@code true} if the bot was found and removed
     */
    public static boolean remove(final String name) {
        final LabBot bot = BOTS.remove(name.toLowerCase(Locale.ROOT));
        if (bot == null) {
            return false;
        }
        bot.level().getServer().getPlayerList().remove(bot);
        return true;
    }

    /**
     * Removes every bot. Mandatory on server shutdown: a bot left behind would keep
     * holding chunks and taking mobcap.
     */
    public static int removeAll() {
        final List<String> names = new ArrayList<>(BOTS.keySet());
        int removed = 0;
        for (final String name : names) {
            if (remove(name)) {
                removed++;
            }
        }
        return removed;
    }

    public static List<LabBot> bots() {
        return List.copyOf(BOTS.values());
    }

    public static @Nullable LabBot get(final String name) {
        return BOTS.get(name.toLowerCase(Locale.ROOT));
    }

    public static boolean isBot(final ServerPlayer player) {
        return player instanceof LabBot;
    }

    public static int count() {
        return BOTS.size();
    }
}
