package pl.skidam.automodpack.networking.connection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;

public final class AutoModpackConnectionOutboundBridgeHandler extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "automodpack_connection_outbound_bridge";

    private final AutoModpackConnectionManager manager;

    public AutoModpackConnectionOutboundBridgeHandler(AutoModpackConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        manager.bindOutboundBridgeContext(context);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        manager.unbindOutboundBridgeContext(context);
    }
}
