package io.papermc.paper.lab.bot;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.lab.Lab;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Реестр AFK-ботов и их тик в правильной фазе.
 *
 * <p>Боты — вмешательство, поэтому доступны только в режиме {@link io.papermc.paper.lab.LabMode#CONTROL}.
 * Прогон с ботами <b>не сравнивается</b> напрямую с вариантами A/B/C: дополнительный игрок
 * сам меняет chunk tickets, {@code NearbyPlayers} и локальный мобкап.
 *
 * <p>Все операции обязаны выполняться на главном потоке сервера.
 */
public final class LabBotRegistry {

    /** Имя → бот. Порядок вставки сохраняется, чтобы тик был детерминированным. */
    private static final Map<String, LabBot> BOTS = new LinkedHashMap<>();

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
        if (BOTS.isEmpty()) {
            return;
        }
        // Копия: doTick() может привести к удалению бота (смерть, кик).
        for (final LabBot bot : new ArrayList<>(BOTS.values())) {
            if (bot.isRemoved() || bot.hasDisconnected()) {
                BOTS.remove(bot.labName().toLowerCase(Locale.ROOT));
                continue;
            }
            try {
                bot.tickConnectionPhase();
            } catch (final Throwable t) {
                org.apache.logging.log4j.LogManager.getLogger("PaperLab")
                    .error("Ошибка тика бота {}; бот удаляется", bot.labName(), t);
                remove(bot.labName());
            }
        }
    }

    /**
     * Создаёт бота и регистрирует его как обычного игрока.
     *
     * @return сообщение об ошибке, либо {@code null} при успехе
     */
    public static @Nullable String spawn(final MinecraftServer server,
                                         final String name,
                                         final ServerLevel level,
                                         final Vec3 pos,
                                         final float yaw,
                                         final float pitch,
                                         final GameType gameMode) {
        if (!Lab.mode().mayIntervene()) {
            return "боты доступны только в режиме CONTROL (сейчас " + Lab.mode().name() + ")";
        }
        if (name.isBlank() || name.length() > 16) {
            return "имя бота должно быть от 1 до 16 символов";
        }
        final String key = name.toLowerCase(Locale.ROOT);
        if (BOTS.containsKey(key)) {
            return "бот с именем '" + name + "' уже существует";
        }
        if (server.getPlayerList().getPlayerByName(name) != null) {
            return "игрок с именем '" + name + "' уже на сервере";
        }

        // Offline-UUID: боту не нужен профиль Mojang, и обращаться к их серверам
        // ради стенда незачем. Как следствие, у бота не будет скина.
        final UUID uuid = UUIDUtil.createOfflinePlayerUUID(name);
        if (server.getPlayerList().getPlayer(uuid) != null) {
            return "игрок с таким UUID уже на сервере";
        }
        final GameProfile profile = new GameProfile(uuid, name);

        final LabBot bot = new LabBot(server, level, profile, ClientInformation.createDefault());
        final LabBotConnection connection = new LabBotConnection();

        server.getPlayerList().placeNewPlayer(
            connection, bot, CommonListenerCookie.createInitial(profile, false));

        // placeNewPlayer размещает игрока по сохранённым/спавновым координатам.
        // Переносим на запрошенную точку уже после регистрации, чтобы chunk tickets
        // и NearbyPlayers пересчитались обычным путём.
        bot.teleportTo(level, pos.x, pos.y, pos.z, java.util.Set.of(), yaw, pitch, true);
        bot.setHealth(bot.getMaxHealth());
        bot.gameMode.changeGameModeForPlayer(gameMode);
        // Шаг по умолчанию: без этого бот может застревать на полублоках.
        final var stepHeight = bot.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(0.6D);
        }

        BOTS.put(key, bot);
        return null;
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
     * Удаляет всех ботов. Обязательно при остановке сервера и при выходе из CONTROL:
     * оставленный бот продолжал бы держать чанки и занимать мобкап.
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

    /**
     * Сообщение для {@code /paper lab bot list}.
     */
    public static List<String> describeAll() {
        if (BOTS.isEmpty()) {
            return Collections.singletonList("ботов нет");
        }
        final List<String> out = new ArrayList<>(BOTS.size());
        for (final LabBot bot : BOTS.values()) {
            out.add(String.format(Locale.ROOT,
                "%s | %s | %.1f %.1f %.1f | чанк %d,%d | %s | здоровье %.1f",
                bot.labName(),
                bot.level().getWorld().getName(),
                bot.getX(), bot.getY(), bot.getZ(),
                bot.chunkPosition().x(), bot.chunkPosition().z(),
                bot.gameMode.getGameModeForPlayer().getName(),
                bot.getHealth()));
        }
        return out;
    }

    /**
     * Диагностика: сообщение о том, что бот не эталон игрока.
     */
    public static Component equivalenceDisclaimer() {
        return Component.literal(
            "бот — настоящий ServerPlayer с заглушкой соединения; порядок doTick/tick совпадает "
                + "с живым игроком, но эквивалентность НЕ доказана и требует отдельных тестов");
    }
}
