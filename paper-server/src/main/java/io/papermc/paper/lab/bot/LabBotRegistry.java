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
 * Реестр AFK-ботов и их тик в правильной фазе.
 *
 * <p>Все операции обязаны выполняться на главном потоке сервера, кроме явно
 * помеченного разрешения имени.
 */
public final class LabBotRegistry {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("PaperLab");

    /** Имя → бот. Порядок вставки сохраняется, чтобы тик был детерминированным. */
    private static final Map<String, LabBot> BOTS = new LinkedHashMap<>();

    /**
     * Чем создавался бот — всё, что нужно, чтобы поднять его заново.
     *
     * <p>{@code skinName} и {@code inGameName} различаются, когда включён суффикс:
     * скин берётся по первому, а UUID и место в таб-листе — по второму.
     */
    public record Spec(String skinName, String inGameName, ResourceKey<Level> dimension,
                       Vec3 pos, float yaw, float pitch, GameType gameMode, boolean flying) {
    }

    /** Бот, которого ждём поднять: спецификация и сколько тиков осталось. */
    private record Pending(Spec spec, boolean autoRespawn, int ticksLeft) {
    }

    /**
     * Пауза перед подъёмом. Секунда нужна не для красоты: смерть роняет предметы и
     * запускает событие, и поднимать игрока в том же тике — верный способ поймать
     * состояние, которого движок не ожидает.
     */
    private static final int RESPAWN_DELAY_TICKS = 20;

    private static final List<Pending> PENDING = new ArrayList<>();

    /**
     * Имена, для которых профиль уже запрашивается. Резолв уходит в сеть и возвращается
     * через несколько тиков; без этой пометки два быстрых {@code spawn} подряд создали бы
     * двух ботов с одним именем.
     */
    private static final Set<String> SPAWNING = new HashSet<>();

    private LabBotRegistry() {
    }

    /**
     * Вызывается хуком из {@code MinecraftServer} сразу после {@code tickConnection()}.
     *
     * <p>Это единственное место, где боту вызывается {@code doTick()} — ровно та фаза
     * ({@code tickChildren}: levels → <b>connection</b> → players) и ровно тот порядок
     * относительно {@code tick()}, что и у живого игрока. Без хука бот получал бы
     * {@code tick()} от уровня, но не {@code doTick()}, и, например, не обрабатывались бы
     * его таймеры еды и подъёма.
     */
    public static void tickConnectionPhase() {
        tickPending();
        if (BOTS.isEmpty()) {
            return;
        }
        // Копия: doTick() может привести к удалению бота (смерть, кик).
        for (final LabBot bot : new ArrayList<>(BOTS.values())) {
            // Мёртвый бот некому респавнить: у него нет клиента, который пришлёт
            // ServerboundClientCommandPacket. Без этого он остаётся призраком в таб-листе,
            // поэтому убираем его штатным путём.
            if (bot.isDeadOrDying() || bot.isRemoved() || bot.hasDisconnected()) {
                // Спецификацию снимаем до удаления: после remove бот уже ничего не помнит.
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
     * Создаёт бота и регистрирует его как обычного игрока.
     *
     * <p><b>Создание асинхронное.</b> Профиль, а с ним скин и плащ, резолвится через
     * сервисы Mojang, поэтому бот появляется на несколько тиков позже возврата отсюда.
     * Синхронно проверяется только то, что видно сразу; об остальном сообщает
     * {@code feedback}, который вызывается уже на главном потоке.
     *
     * <p><b>Про настоящие имена.</b> Если имя принадлежит существующему игроку, бот
     * получает его UUID и его скин. Плата: пока такой бот в игре, сам игрок с этим именем
     * войти не сможет — сервер увидит дублирующийся вход. Поведение осознанное и такое же,
     * как в Carpet.
     *
     * @return сообщение об ошибке, если она видна сразу, иначе {@code null}
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
        // Имя в игре — с суффиксом, скин — по имени без него. Разделение нужно, чтобы
        // бот выглядел как нужный игрок, но не занимал его UUID: иначе сам игрок войти
        // не сможет, сервер увидит дублирующийся вход.
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
                // Профиля может не быть вовсе: имя выдумано или сервер без сети.
                // Это не ошибка — бот просто останется без скина.
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
                // Всё, что дальше, выполняется в колбэке future. Исключение отсюда
                // CompletableFuture проглотит молча: ни в логе, ни в чате не будет
                // ничего, а бот просто не появится. Один раз так и вышло — поэтому
                // ловим сами и говорим вслух.
                try {
                    // Скины лежат в свойствах профиля и от имени не зависят, а вот UUID
                    // зависит: берём его от имени с суффиксом, иначе конфликт с живым
                    // игроком никуда не денется.
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
     * Профиль под именем в игре: свойства (скин, плащ) сохраняются, имя и UUID берутся
     * от имени с суффиксом.
     *
     * <p>Без суффикса профиль остаётся как есть — тогда бот и правда занимает UUID игрока,
     * и это осознанный режим: имя правила пустое, значит пользователь этого и хотел.
     */
    private static GameProfile withName(final GameProfile profile,
                                        final String inGameName,
                                        final String suffix) {
        if (suffix.isEmpty()) {
            return profile;
        }
        // Свойства (в том числе текстуры) переносим конструктором, а не putAll:
        // карта свойств у GameProfile может быть неизменяемой, и putAll тогда бросает.
        return new GameProfile(
            UUIDUtil.createOfflinePlayerUUID(inGameName), inGameName, profile.properties());
    }

    /** Поднять ботов, у которых вышла пауза. */
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
     * Кто это по имени. Сначала спрашиваем кэш имён сервера: для существующего игрока он
     * вернёт настоящий UUID, и бот получит его скин. Для выдуманного имени останется
     * offline-UUID, как и раньше.
     *
     * <p>Выполняется вне главного потока: разрешение имени ходит в сеть.
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

    /** Собственно создание. Только главный поток. */
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

        // Данные игрока нужно загружать явно. Обычный вход читает их до placeNewPlayer,
        // а бота мы конструируем сами, минуя логин, поэтому без этого вызова инвентарь,
        // здоровье и опыт не восстанавливаются между spawn и kill. Сохранение при
        // PlayerList.remove при этом работает и раньше — терялась только загрузка.
        loadSavedData(server, bot);

        // Бот мог сохраниться верхом; иначе он появится на старом транспорте.
        bot.stopRiding();

        // placeNewPlayer размещает игрока по сохранённым/спавновым координатам.
        // Переносим на запрошенную точку уже после регистрации, чтобы chunk tickets
        // и NearbyPlayers пересчитались обычным путём.
        bot.teleportTo(level, pos.x, pos.y, pos.z, java.util.Set.of(), yaw, pitch, true);
        bot.setYHeadRot(yaw);
        bot.setHealth(bot.getMaxHealth());
        bot.gameMode.changeGameModeForPlayer(gameMode);
        bot.getAbilities().flying = flying;
        bot.onUpdateAbilities();
        // Шаг по умолчанию: без этого бот может застревать на полублоках.
        final var stepHeight = bot.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(0.6D);
        }
        // Все слои скина. У бота нет клиента, который прислал бы настройки модели,
        // поэтому без этой строки не видно ни второго слоя, ни плаща.
        bot.getEntityData().set(Avatar.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F);

        // Явная рассылка поворота и позиции: клиенты, которым бота уже показали,
        // иначе увидят его смотрящим в другую сторону до первого движения.
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
     * Читает сохранённые данные бота: инвентарь, здоровье, опыт, эндер-сундук,
     * а также восстанавливает эндер-жемчуг и транспорт, как при обычном входе игрока.
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
     * Удаляет бота штатным путём {@code PlayerList.remove}: сохранение данных,
     * снятие chunk tickets, выход из {@code NearbyPlayers}.
     *
     * @return {@code true}, если бот был найден и удалён
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
     * Удаляет всех ботов. Обязательно при остановке сервера: оставленный бот продолжал бы
     * держать чанки и занимать мобкап.
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
