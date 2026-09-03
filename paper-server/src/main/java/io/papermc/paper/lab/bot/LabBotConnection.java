package io.papermc.paper.lab.bot;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Заглушка сетевого соединения для бота.
 *
 * <p>Соединение нужно потому, что {@code PlayerList.placeNewPlayer} требует {@link Connection}
 * и {@code ServerPlayer} держит на него ссылку. Все исходящие пакеты отбрасываются: у бота нет
 * клиента, которому их отправлять.
 *
 * <p><b>Почему нужен настоящий канал.</b> {@link EmbeddedChannel} ставится, чтобы
 * {@code Connection.isOpen()} возвращал {@code true}. Без этого часть механик, проверяющих
 * открытость соединения игрока, ведёт себя иначе — в частности телепортация эндер-жемчугом
 * к игроку. В Paper 26.2 поле {@code Connection.channel} уже {@code public}, поэтому
 * access transformer не требуется (проверено).
 *
 * <p>Это соединение <b>намеренно не регистрируется</b> в {@code ServerConnectionListener}:
 * оно не должно попадать в общий список соединений, участвовать в player-shuffle и
 * keepalive-логике. Как следствие, {@code Connection.tick()} для него не вызывается,
 * и {@code ServerPlayer.doTick()} приходится вызывать отдельно — это делает
 * {@link LabBotRegistry} в правильной фазе тика.
 */
public final class LabBotConnection extends Connection {

    public LabBotConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
    }

    @Override
    public void setReadOnly() {
        // Клиента нет — переводить соединение в read-only нечего.
    }

    @Override
    public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
        // Исходящие пакеты отбрасываются.
    }

    @Override
    public void handleDisconnection() {
        // Разрыв обрабатывается через PlayerList.remove в LabBotRegistry.
    }

    @Override
    public void setListenerForServerboundHandshake(final PacketListener listener) {
        // Рукопожатия не происходит.
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(final ProtocolInfo<T> protocolInfo, final T listener) {
        // Входящего протокола нет.
    }
}
