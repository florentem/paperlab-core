package io.papermc.paper.lab.chunkmap;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Серверная сторона протокола ChunkDebug — под уже существующий клиентский мод.
 *
 * <p>Формат воспроизведён по исходникам ChunkDebug: пространство имён {@code chunk-debug},
 * версия протокола {@code 4}, тела пакетов кодируются {@link StreamCodec}.
 *
 * <p><b>Почему сервер приходится писать заново.</b> Серверная часть ChunkDebug сделана
 * миксинами в ванильные {@code ChunkMap}, {@code DistanceManager}, {@code TicketStorage}
 * и {@code TickingTracker}. Paper заменяет всю эту подсистему на Moonrise, поэтому
 * донорские миксины неприменимы: данные берутся из
 * {@code ChunkHolderManager}/{@code NewChunkHolder} (см. {@link ChunkMapTracker}).
 */
public final class ChunkMapProtocol {

    public static final String NAMESPACE = "chunk-debug";
    public static final int PROTOCOL_VERSION = 4;

    private ChunkMapProtocol() {
    }

    public static Identifier id(final String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    // --- вспомогательные кодеки ---

    /**
     * Измерение передаётся как {@code Identifier}, а не как индекс реестра:
     * так клиент не зависит от порядка регистрации на сервере.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ResourceKey<Level>> DIMENSION =
        StreamCodec.of(
            (buf, key) -> buf.writeIdentifier(key.identifier()),
            buf -> ResourceKey.create(Registries.DIMENSION, buf.readIdentifier())
        );

    /** {@code null} стадия означает «чанк ещё не сгенерирован». */
    public static final StreamCodec<ByteBuf, Optional<ChunkStatus>> OPTIONAL_CHUNK_STATUS =
        StreamCodec.of(
            (buf, status) -> {
                final FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
                friendly.writeBoolean(status.isPresent());
                status.ifPresent(value -> friendly.writeIdentifier(
                    BuiltInRegistries.CHUNK_STATUS.getKey(value)));
            },
            buf -> {
                final FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
                if (!friendly.readBoolean()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(BuiltInRegistries.CHUNK_STATUS.getValue(friendly.readIdentifier()));
            }
        );

    /**
     * Ticket: тип, уровень и сколько тиков осталось.
     *
     * <p>Тип передаётся именем из реестра. У Paper есть собственные типы tickets,
     * которых нет в ваниле; клиент показывает их как есть, поэтому подмена или
     * фильтрация здесь недопустима — иначе картина tickets перестанет быть настоящей.
     */
    public record TicketInfo(String type, int level, long ticksLeft) {
    }

    public static final StreamCodec<FriendlyByteBuf, TicketInfo> TICKET = StreamCodec.of(
        (buf, ticket) -> {
            buf.writeUtf(ticket.type());
            buf.writeInt(ticket.level());
            buf.writeVarLong(ticket.ticksLeft());
        },
        buf -> new TicketInfo(buf.readUtf(), buf.readInt(), buf.readVarLong())
    );

    public static final StreamCodec<FriendlyByteBuf, List<TicketInfo>> TICKETS =
        ByteBufCodecs.<FriendlyByteBuf, TicketInfo>list().apply(TICKET);

    public static String ticketTypeName(final TicketType type) {
        final Identifier key = BuiltInRegistries.TICKET_TYPE.getKey(type);
        return key == null ? id("unregistered").toString() : key.toString();
    }

    // --- модель чанка ---

    /**
     * @param position            координаты чанка
     * @param stage               стадия генерации; {@code null} — не сгенерирован
     * @param tickets             tickets, удерживающие чанк
     * @param statusLevel         уровень ticket'а
     * @param tickingStatusLevel  уровень, учитывающий ticking-распространение
     * @param unloading           чанк помечен на выгрузку
     */
    public record ChunkInfo(
        ChunkPos position,
        @Nullable ChunkStatus stage,
        List<TicketInfo> tickets,
        int statusLevel,
        int tickingStatusLevel,
        boolean unloading
    ) {
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkInfo> CHUNK_INFO = StreamCodec.of(
        (buf, data) -> {
            buf.writeChunkPos(data.position());
            buf.writeInt(data.statusLevel());
            buf.writeInt(data.tickingStatusLevel());
            buf.writeBoolean(data.unloading());
            OPTIONAL_CHUNK_STATUS.encode(buf, Optional.ofNullable(data.stage()));
            TICKETS.encode(buf, data.tickets());
        },
        buf -> {
            final ChunkPos pos = buf.readChunkPos();
            final int statusLevel = buf.readInt();
            final int tickingStatusLevel = buf.readInt();
            final boolean unloading = buf.readBoolean();
            final ChunkStatus stage = OPTIONAL_CHUNK_STATUS.decode(buf).orElse(null);
            final List<TicketInfo> tickets = TICKETS.decode(buf);
            return new ChunkInfo(pos, stage, tickets, statusLevel, tickingStatusLevel, unloading);
        }
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, Collection<ChunkInfo>> CHUNK_INFO_LIST =
        ByteBufCodecs.collection(ArrayList::new, CHUNK_INFO);

    // Записи-пакеты здесь не объявляются: тело кодируется напрямую в ChunkMapWire
    // через DiscardedPayload, поэтому регистрировать типы в реестре протокола не нужно.
}
