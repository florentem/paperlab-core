package io.papermc.paper.lab.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * AFK-бот: настоящий {@link ServerPlayer} без клиента.
 *
 * <p><b>Один канонический тик.</b> Класс намеренно <i>не переопределяет</i>
 * {@code tick()}. Обоснование — подтверждено кодом:
 *
 * <ul>
 *   <li>игроки добавляются в {@code ServerLevel.entityTickList}
 *       (колбэки сущностей, {@code ServerLevel.java:2841}), поэтому
 *       {@code ServerPlayer.tick()} вызывается обычным тиком сущностей уровня —
 *       ровно как у живого игрока;</li>
 *   <li>{@code ServerPlayer.doTick()} у живого игрока вызывается из
 *       {@code ServerGamePacketListenerImpl.tick()} ({@code :387}), которую тикает
 *       {@code Connection.tick()} из {@code ServerConnectionListener.tick()};</li>
 *   <li>внутри {@code MinecraftServer.tickChildren} порядок фаз такой:
 *       {@code levels} (тик уровней и сущностей) → {@code connection}
 *       ({@code tickConnection()}, {@code MinecraftServer.java:1838}) → {@code players}.</li>
 * </ul>
 *
 * Значит у живого игрока порядок внутри тика: <b>{@code tick()} → затем {@code doTick()}</b>,
 * причём в разных фазах. Поэтому {@link LabBotRegistry} вызывает {@code doTick()} из хука,
 * поставленного сразу после {@code tickConnection()}, а {@code tick()} приходит сам от уровня.
 * Так совпадают и фаза, и порядок.
 *
 * <p>Fabric Carpet добивается того же относительного порядка иначе: переопределяет
 * {@code tick()} и вызывает внутри {@code super.tick()}, затем {@code doTick()}. Порядок
 * получается верный, но оба вызова оказываются в фазе сущностей, до фазы соединений.
 * Здесь выбран хук, чтобы совпадала ещё и фаза.
 *
 * <p><b>Что НЕ доказано:</b> эквивалентность бота живому игроку. Совпадение порядка вызовов
 * — необходимое условие, но не доказательство. Отдельные проверки нужны минимум по:
 * lifecycle (spawn/remove/death/respawn/restart), chunk tickets, попаданию в
 * {@code NearbyPlayers} и локальный мобкап, EAR-иммунитетам поблизости, боевым действиям.
 * До прохождения этих тестов бот — инструмент нагрузки, а не эталон игрока.
 */
public final class LabBot extends ServerPlayer {

    private final String labName;
    private final LabActionPack actions = new LabActionPack(this);

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

    /**
     * Вызывается {@link LabBotRegistry} в фазе соединений — там, где живому игроку
     * это делает его packet listener.
     */
    void tickConnectionPhase() {
        // Действия идут до doTick(): у живого игрока в этой фазе сначала
        // обрабатываются входящие пакеты, и только потом вызывается doTick().
        this.actions.tick();
        this.doTick();
    }
}
