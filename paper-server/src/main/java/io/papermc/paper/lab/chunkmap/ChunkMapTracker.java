package io.papermc.paper.lab.chunkmap;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Снимок состояния чанков мира для карты — из Moonrise, а не из ванильного {@code ChunkMap}.
 *
 * <p>Донорский ChunkDebug читает данные миксинами в {@code ChunkMap},
 * {@code DistanceManager}, {@code TicketStorage} и {@code TickingTracker}. Paper заменяет
 * всю эту подсистему, поэтому источник здесь другой:
 *
 * <ul>
 *   <li>{@code ChunkHolderManager.getChunkHolders()} — все живые holder'ы;</li>
 *   <li>{@code NewChunkHolder.getTicketLevel()} — уровень ticket'а;</li>
 *   <li>{@code NewChunkHolder.getChunkStatus()} — фактический {@code FullChunkStatus};</li>
 *   <li>{@code NewChunkHolder.getCurrentGenStatus()} — стадия генерации;</li>
 *   <li>{@code ChunkHolderManager.getTicketsAt(x, z)} — сами tickets.</li>
 * </ul>
 *
 * <p>Читает только уже имеющееся состояние: чанки не грузит, tickets не добавляет.
 * Должен вызываться на главном потоке сервера.
 */
public final class ChunkMapTracker {

    private ChunkMapTracker() {
    }

    /**
     * Полный снимок.
     *
     * @param withTickets собирать ли tickets по каждому чанку. Это отдельный запрос
     *                    с блокировкой области на чанк, поэтому для больших миров
     *                    дороже самого обхода holder'ов.
     */
    public static List<ChunkMapProtocol.ChunkInfo> snapshot(final ServerLevel level,
                                                            final boolean withTickets) {
        final var scheduler = level.moonrise$getChunkTaskScheduler();
        final List<NewChunkHolder> holders = scheduler.chunkHolderManager.getChunkHolders();
        final List<ChunkMapProtocol.ChunkInfo> out = new ArrayList<>(holders.size());

        for (final NewChunkHolder holder : holders) {
            final ChunkMapProtocol.ChunkInfo info = describe(level, holder, withTickets);
            if (info != null) {
                out.add(info);
            }
        }
        return out;
    }

    private static ChunkMapProtocol.@org.checkerframework.checker.nullness.qual.Nullable ChunkInfo describe(
        final ServerLevel level, final NewChunkHolder holder, final boolean withTickets) {

        final int ticketLevel = holder.getTicketLevel();
        if (ticketLevel > ChunkLevel.MAX_LEVEL) {
            // Holder есть, но уровень уже за пределом загрузки — показывать нечего.
            return null;
        }

        final ChunkPos pos = new ChunkPos(holder.chunkX, holder.chunkZ);

        final ChunkStatus stage = holder.getCurrentGenStatus();

        // Клиент вычисляет FullChunkStatus как fullStatus(max(statusLevel, tickingStatusLevel)).
        // У Moonrise ticking-распространение уже учтено в самом ticket level, поэтому
        // оба уровня совпадают: разделять их нечем, и подставлять разное было бы обманом.
        final int statusLevel = ticketLevel;
        final int tickingStatusLevel = ticketLevel;

        final List<ChunkMapProtocol.TicketInfo> tickets;
        if (withTickets) {
            tickets = ticketsAt(level, pos.x(), pos.z());
        } else {
            tickets = List.of();
        }

        // Флага «поставлен в очередь на выгрузку» Moonrise публично не выставляет
        // (есть только внутренний checkUnload и isSafeToUnload(), возвращающий причину).
        // Подставлять сюда что-то похожее нельзя: клиент раскрасит чанк как выгружающийся,
        // и картина станет ложной. Поэтому честный false, пока не появится настоящий признак.
        final boolean unloading = false;

        return new ChunkMapProtocol.ChunkInfo(
            pos, stage, tickets, statusLevel, tickingStatusLevel, unloading);
    }

    /** Tickets конкретного чанка в виде, пригодном для передачи клиенту. */
    public static List<ChunkMapProtocol.TicketInfo> ticketsAt(final ServerLevel level,
                                                              final int chunkX, final int chunkZ) {
        final List<Ticket> raw = level.moonrise$getChunkTaskScheduler()
            .chunkHolderManager.getTicketsAt(chunkX, chunkZ);
        if (raw.isEmpty()) {
            return List.of();
        }
        final List<ChunkMapProtocol.TicketInfo> out = new ArrayList<>(raw.size());
        for (final Ticket ticket : raw) {
            out.add(new ChunkMapProtocol.TicketInfo(
                ChunkMapProtocol.ticketTypeName(ticket.getType()),
                ticket.getTicketLevel(),
                ticket.moonrise$getRemoveDelay()));
        }
        return out;
    }
}
