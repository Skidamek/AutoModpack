package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.TrafficShaper;

import java.util.List;

import static pl.skidam.automodpack_core.protocol.NetUtils.*;

public class ProtocolServerHandler extends ByteToMessageDecoder {

    private final SslContext sslCtx;

    public ProtocolServerHandler(SslContext sslCtx) {
        this.sslCtx = sslCtx;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }

        int magic = in.getInt(0);
        if (magic == MAGIC_AMMC) {
            // Consume the packet
            in.skipBytes(in.readableBytes());

            // Send acknowledgment
            ByteBuf response = ctx.alloc().buffer(4);
            response.writeInt(MAGIC_AMOK);
            ctx.writeAndFlush(response);

            // Remove all existing handlers from the pipeline
            var handlers = ctx.pipeline().toMap();
            handlers.forEach((name, handler) -> ctx.pipeline().remove(handler));

//                InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
//                boolean isLocalConnection = AddressHelpers.isLocal(address);
//
//                // Use compression only for non-local connections
//                ctx.pipeline().channel().attr(NetUtils.USE_COMPRESSION).set(!isLocalConnection);

            ctx.pipeline().channel().attr(NettyServer.USE_COMPRESSION).set(true);

            // Set up the pipeline for our protocol
            ctx.pipeline().addLast("traffic-shaper", TrafficShaper.trafficShaper.getTrafficShapingHandler());
            if (sslCtx != null) { // If SSL context is provided, add TLS handler}
                ctx.pipeline().addLast("tls", sslCtx.newHandler(ctx.alloc()));
            }
            ctx.pipeline() // Add the rest
                    .addLast("zstd-encoder", new ZstdEncoder())
                    .addLast("zstd-decoder", new ZstdDecoder())
                    .addLast("chunked-write", new ChunkedWriteHandler())
                    .addLast("protocol-msg-decoder", new ProtocolMessageDecoder())
                    .addLast("msg-handler", new ServerMessageHandler())
                    .addLast("error-printer", new ErrorPrinter());
        }

        // Always remove this handler after processing if its still there
        if (ctx.pipeline().get(this.getClass()) != null) {
            ctx.pipeline().remove(this);
        }
    }
}