package io.papermc.paper.lab.chunkmap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Подписки на карту чанков и рассылка снимков.
 *
 * <p>Клиент — существующий мод ChunkDebug. Обмен: клиент присылает {@code hello} со своей
 * версией протокола, сервер отвечает своей; затем {@code start_watching}/{@code stop_watching}
 * по измерениям, а сервер шлёт {@code chunk_data}.
 *
 * <p>Дельты пока не считаются: каждый раз уходит полный снимок с {@code initial = true}.
 * Так честнее для первой версии — клиент всегда видит согласованное состояние, а не
 * результат склейки, в которой мог потеряться апдейт. Дельты имеет смысл добавлять только
 * вместе с измерением их стоимости.
 */
public final class ChunkMapService {

    /** Как часто шлём снимок подписчикам. */
    private static final int PERIOD_TICKS = 20;

    /** Игрок → измерения, за которыми он следит. */
    private static final Map<UUID, Set<ResourceKey<Level>>> WATCHERS = new HashMap<>();

    /** Игроки, чей клиент прошёл рукопожатие. */
    private static final Set<UUID> HANDSHAKED = new HashSet<>();

    private ChunkMapService() {
    }

    /**
     * Разбор входящего пакета нашего канала. Вызывается из хука в обработчике
     * custom payload: там уже есть и {@code Identifier}, и сырые байты.
     *
     * @return true, если пакет наш и обработан
     */
    public static boolean handleIncoming(final ServerPlayer player, final Identifier id, final byte[] data) {
        if (!ChunkMapWire.isOurs(id)) {
            return false;
        }
        final var registries = player.registryAccess();
        try {
            if (ChunkMapWire.HELLO.equals(id)) {
                onHello(player, ChunkMapWire.decodeHello(data, registries));
            } else if (ChunkMapWire.BYE.equals(id)) {
                onBye(player);
            } else if (ChunkMapWire.START_WATCHING.equals(id)) {
                onStartWatching(player, ChunkMapWire.decodeDimension(data, registries));
            } else if (ChunkMapWire.STOP_WATCHING.equals(id)) {
                onStopWatching(player, ChunkMapWire.decodeDimension(data, registries));
            }
        } catch (final Throwable t) {
            // Некорректное тело от клиента не должно ронять обработку пакетов.
            org.slf4j.LoggerFactory.getLogger("PaperLab")
                .warn("ChunkDebug: испорченный пакет {} от {}", id, player.getScoreboardName(), t);
        }
        return true;
    }


    /**
     * Объявляет клиенту, что сервер принимает наши каналы.
     *
     * <p>Без этого клиентский ChunkDebug пишет «unavailable» и даже не пробует
     * прислать {@code hello}: сетевой слой Fabric считает канал недоступным, пока сервер
     * не перечислил его в {@code minecraft:register}. Paper объявляет только каналы,
     * зарегистрированные плагинами через Bukkit Messenger, — наших там нет,
     * поэтому объявляем сами, сразу после штатного {@code sendSupportedChannels}.
     *
     * <p>Формат тела: имена каналов, разделённые нулевым байтом (не длиной строки) —
     * так же, как их разбирает входящий обработчик.
     */
    public static void announceChannels(final ServerPlayer player) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (final Identifier channel : new Identifier[]{
            ChunkMapWire.HELLO, ChunkMapWire.BYE,
            ChunkMapWire.START_WATCHING, ChunkMapWire.STOP_WATCHING,
            ChunkMapWire.CHUNK_DATA, ChunkMapWire.CHUNK_UNLOAD, ChunkMapWire.CHUNK_REFRESH}) {
            out.writeBytes(channel.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(0);
        }
        send(player, Identifier.withDefaultNamespace("register"), out.toByteArray());
    }

    public static void onHello(final ServerPlayer player, final int clientVersion) {
        HANDSHAKED.add(player.getUUID());
        send(player, ChunkMapWire.HELLO,
            ChunkMapWire.encodeHello(ChunkMapProtocol.PROTOCOL_VERSION, player.registryAccess()));
        if (clientVersion != ChunkMapProtocol.PROTOCOL_VERSION) {
            // Версии не совпали — работать всё равно пробуем, но помечаем это в логе:
            // молчаливое расхождение протокола выглядит как «карта не работает».
            org.slf4j.LoggerFactory.getLogger("PaperLab").warn(
                "ChunkDebug: клиент {} использует протокол {}, сервер {}",
                player.getScoreboardName(), clientVersion, ChunkMapProtocol.PROTOCOL_VERSION);
        }
    }

    public static void onBye(final ServerPlayer player) {
        HANDSHAKED.remove(player.getUUID());
        WATCHERS.remove(player.getUUID());
    }

    public static void onStartWatching(final ServerPlayer player, final ResourceKey<Level> dimension) {
        WATCHERS.computeIfAbsent(player.getUUID(), key -> new HashSet<>()).add(dimension);
        // Сразу отдаём снимок, чтобы карта не оставалась пустой до следующего периода.
        final ServerLevel level = player.level().getServer().getLevel(dimension);
        if (level != null) {
            sendSnapshot(player, level);
        }
    }

    public static void onStopWatching(final ServerPlayer player, final ResourceKey<Level> dimension) {
        final Set<ResourceKey<Level>> dims = WATCHERS.get(player.getUUID());
        if (dims != null) {
            dims.remove(dimension);
            if (dims.isEmpty()) {
                WATCHERS.remove(player.getUUID());
            }
        }
    }

    public static void onDisconnect(final ServerPlayer player) {
        onBye(player);
    }

    /** Вызывается из хука раз в тик. */
    public static void tick(final MinecraftServer server) {
        if (WATCHERS.isEmpty() || server.getTickCount() % PERIOD_TICKS != 0) {
            return;
        }
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final Set<ResourceKey<Level>> dims = WATCHERS.get(player.getUUID());
            if (dims == null) {
                continue;
            }
            for (final ResourceKey<Level> dimension : dims) {
                final ServerLevel level = server.getLevel(dimension);
                if (level != null) {
                    sendSnapshot(player, level);
                }
            }
        }
    }

    private static void sendSnapshot(final ServerPlayer player, final ServerLevel level) {
        final List<ChunkMapProtocol.ChunkInfo> chunks = ChunkMapTracker.snapshot(level, true);
        send(player, ChunkMapWire.CHUNK_DATA, ChunkMapWire.encodeChunkData(
            level.dimension(), chunks, (int) level.getGameTime(), true, player.registryAccess()));
    }

    public static boolean watching(final ServerPlayer player) {
        return WATCHERS.containsKey(player.getUUID());
    }

    public static int watcherCount() {
        return WATCHERS.size();
    }

    private static void send(final ServerPlayer player, final Identifier channel, final byte[] body) {
        player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(channel, body)));
    }
}
