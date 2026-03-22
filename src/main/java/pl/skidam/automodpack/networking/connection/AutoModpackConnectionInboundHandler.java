package pl.skidam.automodpack.networking.connection;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionFrame;

public final class AutoModpackConnectionInboundHandler extends ChannelInboundHandlerAdapter {
    public static final String NAME = "automodpack_connection_inbound";

    private final AutoModpackConnectionManager manager;

    public AutoModpackConnectionInboundHandler(AutoModpackConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object msg) {
        if (!(msg instanceof ByteBuf buffer)) {
            context.fireChannelRead(msg);
            return;
        }

        if (!AutoModpackConnectionFrame.matches(buffer)) {
            context.fireChannelRead(msg);
            return;
        }

        try (AutoModpackConnectionFrame frame = AutoModpackConnectionFrame.decode(buffer)) {
            manager.handleFrame(frame);
        } catch (Throwable error) {
            manager.protocolError("Failed to handle AutoModpack connection frame", error);
        } finally {
            ReferenceCountUtil.safeRelease(buffer);
        }
    }
}
