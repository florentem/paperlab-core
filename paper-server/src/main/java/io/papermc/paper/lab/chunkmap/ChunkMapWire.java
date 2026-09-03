package io.papermc.paper.lab.chunkmap;

import io.netty.buffer.Unpooled;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Перевод пакетов карты чанков в сырые байты и обратно.
 *
 * <p><b>Почему не регистрируем кодеки в протоколе.</b> Paper хранит тело неизвестного
 * канала как есть: {@code DiscardedPayload(Identifier id, byte[] data)} — и на приём,
 * и на отправку. Значит можно кодировать тело самим и не трогать реестр типов пакетов.
 * Патч в {@code src/minecraft} остаётся в одну строку на приём, а протокольные структуры
 * живут обычным кодом.
 */
public final class ChunkMapWire {

    private ChunkMapWire() {
    }

    private static RegistryFriendlyByteBuf buf(final RegistryAccess registries) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
    }

    private static RegistryFriendlyByteBuf wrap(final byte[] data, final RegistryAccess registries) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries);
    }

    private static byte[] drain(final RegistryFriendlyByteBuf buf) {
        final byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    // --- исходящие ---

    public static byte[] encodeHello(final int version, final RegistryAccess registries) {
        final RegistryFriendlyByteBuf buf = buf(registries);
        buf.writeInt(version);
        return drain(buf);
    }

    public static byte[] encodeChunkData(final ResourceKey<Level> dimension,
                                         final Collection<ChunkMapProtocol.ChunkInfo> chunks,
                                         final int tick,
                                         final boolean initial,
                                         final RegistryAccess registries) {
        final RegistryFriendlyByteBuf buf = buf(registries);
        ChunkMapProtocol.DIMENSION.encode(buf, dimension);
        ChunkMapProtocol.CHUNK_INFO_LIST.encode(buf, chunks);
        buf.writeInt(tick);
        buf.writeBoolean(initial);
        return drain(buf);
    }

    public static byte[] encodeChunkUnload(final ResourceKey<Level> dimension,
                                           final List<Long> chunks,
                                           final RegistryAccess registries) {
        final RegistryFriendlyByteBuf buf = buf(registries);
        ChunkMapProtocol.DIMENSION.encode(buf, dimension);
        buf.writeVarInt(chunks.size());
        for (final long pos : chunks) {
            buf.writeLong(pos);
        }
        return drain(buf);
    }

    // --- входящие ---

    public static int decodeHello(final byte[] data, final RegistryAccess registries) {
        return wrap(data, registries).readInt();
    }

    public static ResourceKey<Level> decodeDimension(final byte[] data, final RegistryAccess registries) {
        return ChunkMapProtocol.DIMENSION.decode(wrap(data, registries));
    }

    // --- идентификаторы каналов ---

    public static final Identifier HELLO = ChunkMapProtocol.id("hello");
    public static final Identifier BYE = ChunkMapProtocol.id("bye");
    public static final Identifier START_WATCHING = ChunkMapProtocol.id("start_watching");
    public static final Identifier STOP_WATCHING = ChunkMapProtocol.id("stop_watching");
    public static final Identifier CHUNK_DATA = ChunkMapProtocol.id("chunk_data");
    public static final Identifier CHUNK_UNLOAD = ChunkMapProtocol.id("chunk_unload");
    public static final Identifier CHUNK_REFRESH = ChunkMapProtocol.id("chunk_refresh");

    /** Наш ли это канал. Проверяется до разбора тела. */
    public static boolean isOurs(final Identifier id) {
        return ChunkMapProtocol.NAMESPACE.equals(id.getNamespace());
    }
}
