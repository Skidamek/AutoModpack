package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.GlobalVariables.serverConfig;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.TrafficShaper;

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

        // Server should always support AMMC/MH protocol (magic packets) (preferred way to connect, required for hosting on Minecraft port and good for backwards compatibility)
        int magic = in.getInt(0);
        if (magic == MAGIC_AMMH) { // AMMH magic packet for playitgg, it requires a hostname within the packet
            if (in.readableBytes() < 6) { // wait for hostname length
                return;
            }

            // Consume the 4-byte magic packet
            in.skipBytes(4);

            short hostnameLength = in.readShort();
            if (in.readableBytes() < hostnameLength) {
                return;
            }
            byte[] hostnameBytes = new byte[hostnameLength];
            in.readBytes(hostnameBytes);
            String hostname = new String(hostnameBytes, StandardCharsets.UTF_8);
            LOGGER.debug("Received AMMH connection request for hostname: " + hostname);

            // Send acknowledgment
            sendAck(ctx);

            // Remove all existing handlers from the pipeline
            var handlers = ctx.pipeline().toMap();
            handlers.forEach((name, handler) -> ctx.pipeline().remove(handler));

            setupPipeline(ctx);
        } else if (magic == MAGIC_AMMC) {
            LOGGER.debug("Received AMMC connection request");

            // Consume the 4-byte magic packet
            in.skipBytes(4);

            // Send acknowledgment
            sendAck(ctx);

            // Remove all existing handlers from the pipeline
            var handlers = ctx.pipeline().toMap();
            handlers.forEach((name, handler) -> ctx.pipeline().remove(handler));

            setupPipeline(ctx);
        } else if (sslCtx == null || serverConfig.bindPort != -1) {
            setupPipeline(ctx);
        }

        // Always remove this handler after processing if its still there
        if (ctx.channel().pipeline().get(this.getClass()) != null) {
            ctx.channel().pipeline().remove(this);
        }
    }

    private void sendAck(ChannelHandlerContext ctx) {
        ByteBuf response = ctx.alloc().buffer(4);
        response.writeInt(MAGIC_AMOK);
        ctx.writeAndFlush(response);
    }

    private void setupPipeline(ChannelHandlerContext ctx) {
        // add error handler pipeline
        ctx.pipeline().addLast("error-printer-first", new ErrorPrinter());
        ctx.pipeline().addLast("traffic-shaper", TrafficShaper.trafficShaper.getTrafficShapingHandler());
        if (sslCtx != null) { // If SSL context is provided, add TLS handler
            ctx.pipeline().addLast("tls", sslCtx.newHandler(ctx.alloc()));
            LOGGER.debug("Added TLS handler to the pipeline");
        } else {
            LOGGER.debug("No TLS handler added to the pipeline");
        }

        ctx.channel().attr(NettyServer.PROTOCOL_VERSION).set(PROTOCOL_VERSION); // Set protocol version
        ctx.channel().attr(NettyServer.COMPRESSION_TYPE).set(COMPRESSION_ZSTD); // Default compression
        ctx.channel().attr(NettyServer.CHUNK_SIZE).set(DEFAULT_CHUNK_SIZE); // Default chunk size

        ctx.pipeline() // Add the rest
            .addLast("configuration-handler", new ConfigurationHandler())
            .addLast("compression-encoder", new CompressionEncoder())
            .addLast("compression-decoder", new CompressionDecoder())
            .addLast("chunked-write", new ChunkedWriteHandler())
            .addLast("protocol-msg-decoder", new ProtocolMessageDecoder())
            .addLast("msg-handler", new ServerMessageHandler())
            .addLast("error-printer-last", new ErrorPrinter());
    }
}
