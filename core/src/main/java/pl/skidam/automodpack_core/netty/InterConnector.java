package pl.skidam.automodpack_core.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import static pl.skidam.automodpack_core.GlobalVariables.MOD_ID;
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
        if (handler.isAutoModpackRequest(buf)) {
            handler.channelRead(context, buf, msg);
        } else {
            dropConnection(context, msg);
        }
    }

    private void dropConnection(ChannelHandlerContext ctx, Object request) {
        ctx.pipeline().remove(MOD_ID);
        ctx.fireChannelRead(request);
    }
}
