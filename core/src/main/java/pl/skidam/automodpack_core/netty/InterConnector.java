package pl.skidam.automodpack_core.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import static pl.skidam.automodpack_core.GlobalVariables.httpServer;

// We need to check if we want to handle packets internally or not
public class InterConnector extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext context, Object msg) {
        if (!httpServer.shouldRunInternally()) {
            dropConnection(context, msg);
            return;
        }

        HttpServerHandler handler = new HttpServerHandler();
        ByteBuf buf = (ByteBuf) msg;
        buf.markReaderIndex();
        if (handler.isAutoModpackRequest(buf)) {
            buf.resetReaderIndex();
            handler.channelRead(context, buf, msg);
        } else {
            buf.resetReaderIndex();
            dropConnection(context, msg);
        }
    }

    private void dropConnection(ChannelHandlerContext ctx, Object request) {
        ctx.channel().pipeline().remove(this);
        ctx.fireChannelRead(request);
    }
}
