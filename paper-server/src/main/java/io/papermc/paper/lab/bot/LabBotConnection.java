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
 * A stub network connection for a bot.
 *
 * <p>The connection exists because {@code PlayerList.placeNewPlayer} requires a
 * {@link Connection} and {@code ServerPlayer} holds a reference to one. Every outgoing
 * packet is dropped: a bot has no client to send them to.
 *
 * <p><b>Why a real channel is needed.</b> An {@link EmbeddedChannel} is installed so that
 * {@code Connection.isOpen()} returns {@code true}. Without it, mechanics that check
 * whether a player's connection is open behave differently — ender pearl teleportation
 * towards a player, in particular. In Paper 26.2 the {@code Connection.channel} field is
 * already {@code public}, so no access transformer is needed (verified).
 *
 * <p>This connection is <b>deliberately not registered</b> with
 * {@code ServerConnectionListener}: it must not appear in the shared connection list or
 * take part in player-shuffle and keepalive logic. As a consequence
 * {@code Connection.tick()} is never called for it, and {@code ServerPlayer.doTick()} has
 * to be invoked separately — which {@link LabBotRegistry} does in the right tick phase.
 */
public final class LabBotConnection extends Connection {

    public LabBotConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
    }

    @Override
    public void setReadOnly() {
        // There is no client, so there is nothing to switch to read-only.
    }

    @Override
    public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
        // Outgoing packets are dropped.
    }

    @Override
    public void handleDisconnection() {
        // Disconnection is handled through PlayerList.remove in LabBotRegistry.
    }

    @Override
    public void setListenerForServerboundHandshake(final PacketListener listener) {
        // No handshake takes place.
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(final ProtocolInfo<T> protocolInfo, final T listener) {
        // There is no inbound protocol.
    }
}
