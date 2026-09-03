package io.papermc.paper.lab.chunk;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Чтение фактического {@link FullChunkStatus} чанка без загрузки и без добавления tickets.
 *
 * <p><b>Зачем отдельный класс.</b> Штатный {@code ChunkHolder.getTickingChunk()} возвращает
 * чанк при {@code BLOCK_TICKING} <i>или выше</i> ({@code NewChunkHolder.isTickingReady()} —
 * {@code getChunkStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)}). Использовать его как
 * признак entity-ticking — ошибка ровно в той точке, которая для нас важнее всего: на
 * расстоянии {@code d = S+1} чанк является block-ticking, но <b>не</b> entity-ticking.
 * Там работают поршни, репитеры и воронки, а сущности полностью заморожены.
 *
 * <p>При {@code simulation-distance = 5} картина такая:
 * <pre>
 *   d ≤ 5  ENTITY_TICKING   сущности тикают (далее решает EAR), редстоун работает
 *   d = 6  BLOCK_TICKING    сущности заморожены, редстоун и воронки работают
 *   d = 7  FULL             ни то, ни другое; чанк загружен
 *   d ≥ 8  INACCESSIBLE/выгружен (зависит от view-distance и прочих tickets)
 * </pre>
 *
 * <p>Расстояние — Chebyshev по чанкам: {@code max(|dx|, |dz|)}. Это не универсальное правило
 * для любого чанка: дополнительные tickets (портал, эндер-жемчуг, плагин) меняют статус,
 * поэтому инструмент обязан показывать <i>фактический</i> статус, а не расстояние на глаз.
 */
public final class ChunkStatusProbe {

    private ChunkStatusProbe() {
    }

    /**
     * Фактический статус чанка.
     *
     * @return статус либо {@code null}, если чанк не загружен
     */
    public static @Nullable FullChunkStatus statusOf(final ServerLevel level, final ChunkPos pos) {
        return statusOf(level, pos.x(), pos.z());
    }

    public static @Nullable FullChunkStatus statusOf(final ServerLevel level, final int chunkX, final int chunkZ) {
        final NewChunkHolder holder = level.moonrise$getChunkTaskScheduler()
            .chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        if (holder == null) {
            return null;
        }
        return holder.getChunkStatus();
    }

    /**
     * Тикают ли в этом чанке сущности. Именно это условие требуется для обычного
     * {@code entity.tick()} / {@code inactiveTick()} и для проверки EAR.
     */
    public static boolean entityTicking(final ServerLevel level, final ChunkPos pos) {
        final FullChunkStatus status = statusOf(level, pos);
        return status != null && status.isOrAfter(FullChunkStatus.ENTITY_TICKING);
    }

    /**
     * Тикают ли блоки: block events поршня, scheduled block ticks, block entities
     * (в том числе воронки). Истинно и для entity-ticking чанков.
     */
    public static boolean blockTicking(final ServerLevel level, final ChunkPos pos) {
        final FullChunkStatus status = statusOf(level, pos);
        return status != null && status.isOrAfter(FullChunkStatus.BLOCK_TICKING);
    }

    /**
     * Тот самый интересный случай: блоки тикают, сущности — нет.
     */
    public static boolean frozenEntitiesButLiveRedstone(final ServerLevel level, final ChunkPos pos) {
        return statusOf(level, pos) == FullChunkStatus.BLOCK_TICKING;
    }

    /**
     * Человекочитаемое описание для {@code /lab chunk}.
     */
    public static String describe(final ServerLevel level, final ChunkPos pos, final ChunkPos playerChunk) {
        final FullChunkStatus status = statusOf(level, pos);
        final int distance = Math.max(
            Math.abs(pos.x() - playerChunk.x()),
            Math.abs(pos.z() - playerChunk.z())
        );
        final StringBuilder sb = new StringBuilder(128);
        sb.append("чанк ").append(pos.x()).append(", ").append(pos.z())
            .append(" | d=").append(distance).append(" (Chebyshev)")
            .append(" | статус ").append(status == null ? "не загружен" : status.name());
        if (status == null) {
            return sb.toString();
        }
        sb.append('\n').append("  сущности: ")
            .append(status.isOrAfter(FullChunkStatus.ENTITY_TICKING) ? "тикают" : "ЗАМОРОЖЕНЫ")
            .append(" | блоки/редстоун/воронки: ")
            .append(status.isOrAfter(FullChunkStatus.BLOCK_TICKING) ? "работают" : "не тикают");
        if (status == FullChunkStatus.BLOCK_TICKING) {
            sb.append('\n').append("  это тот самый «ленивый» слой: поршень может внешне сдвинуть ")
                .append("замороженную сущность через entity.move(MoverType.PISTON, …), ")
                .append("но своей гравитации, AI и despawn у неё нет");
        }
        return sb.toString();
    }
}
