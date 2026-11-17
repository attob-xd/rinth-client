package rinth.client.net;

import java.net.InetSocketAddress;
import rinth.client.platform.ClientPlatform;
import rinth.client.singleplayer.ChannelLink;
import rinth.net.NetConnectionChannel;
import rinth.net.relay.RelayWorldAddress;
import rinth.platform.api.net.NetConnection;

/**
 * The game's client channel: {@link NetConnectionChannel} over the installed platform's network,
 * a TCP socket or a WebSocket depending on the address ({@code rinth.net.WebSocketAddress}).
 */
public final class PlatformSocketChannel extends NetConnectionChannel {

    /** Netty creates channels reflectively (the bootstrap's channel class), so this must stay public. */
    public PlatformSocketChannel() {}

    @Override
    protected NetConnection open(final InetSocketAddress remote, final NetConnection.Listener listener) {
        if (remote instanceof ChannelLink.Address link) {
            // Singleplayer's server in a worker (ADR 0039): the channel takes its end over.
            return link.open(listener);
        }
        if (remote instanceof rinth.client.singleplayer.GuestLink.Address guest) {
            // A shared world's guest, in singleplayer's worker (ADR 0062): the server takes its end.
            return guest.open(listener);
        }
        if (remote instanceof RelayWorldAddress world) {
            // A shared world's data channel, already open (ADR 0019): the channel takes it over.
            return world.open(listener);
        }
        return openOn(ClientPlatform.get().network(), remote, listener);
    }
}
