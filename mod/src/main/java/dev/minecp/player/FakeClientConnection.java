package dev.minecp.player;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Carpet-style sink connection for a directly constructed ServerPlayerEntity.
 * The player is registered through PlayerManager.onPlayerConnect, but packets
 * have no real client to receive them.
 */
final class FakeClientConnection extends ClientConnection {
    private static final SocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);

    FakeClientConnection() {
        super(NetworkSide.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet) {
        // Intentionally discarded: there is no client process.
    }

    @Override
    public void send(Packet<?> packet, PacketCallbacks callbacks) {
        // Intentionally discarded: there is no client process.
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isChannelAbsent() {
        return false;
    }

    @Override
    public SocketAddress getAddress() {
        return LOOPBACK;
    }

    @Override
    public void tick() {
        // A fake connection has no inbound packet queue.
    }

    @Override
    public void disconnect(Text disconnectReason) {
        // PlayerManager.remove handles the server-side lifecycle.
    }

    @Override
    public void handleDisconnection() {
        // A fake connection never owns a Netty channel.
    }
}
