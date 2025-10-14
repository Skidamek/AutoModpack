package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.GlobalVariables.serverConfig;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.util.List;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.TrafficShaper;
import pl.skidam.automodpack_core.utils.PlatformUtils;

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

        // TODO: move the protocol and compression negotiations after TLS is established
        //  and also make chunk size negotiable
        int magic = in.getInt(0);
        if (magic == MAGIC_AMMC) {
            // Server should always support AMMC protocol (magic packets) (preferred way to connect, required for hosting on Minecraft port and good for backwards compatibility)
            byte negotiatedVersion = PROTOCOL_VERSION_2; // Default to v2
            byte negotiatedCompressionType = COMPRESSION_ZSTD; // Default to Zstd

            // Check if client sent protocol version (v2+ clients send 5 bytes: magic + version)
            if (in.readableBytes() >= 6) {
                in.skipBytes(4); // Skip magic
                byte clientProtocolVersion = in.readByte();
                byte clientCompressionType = in.readByte();

                // Negotiate protocol version (prefer v2 if both support it)
                negotiatedVersion = (clientProtocolVersion >= PROTOCOL_VERSION_2) ? PROTOCOL_VERSION_2 : PROTOCOL_VERSION_1;
                
                // Check if we support the client's compression type
                if (clientCompressionType == COMPRESSION_ZSTD || clientCompressionType == COMPRESSION_GZIP || clientCompressionType == COMPRESSION_NONE) {
                    if (PlatformUtils.isAndroid() && clientCompressionType == COMPRESSION_ZSTD) {
                        negotiatedCompressionType = COMPRESSION_GZIP; // Default to Gzip on Android
                    } else {
                        negotiatedCompressionType = clientCompressionType;
                    }
                }


                // Send acknowledgment with negotiated version and compression type
                ByteBuf response = ctx.alloc().buffer(6);
                response.writeInt(MAGIC_AMOK);
                response.writeByte(negotiatedVersion);
                response.writeByte(negotiatedCompressionType);
                ctx.writeAndFlush(response);

                // Store negotiated values in channel attributes
                ctx.pipeline().channel().attr(NettyServer.PROTOCOL_VERSION).set(negotiatedVersion);
                ctx.pipeline().channel().attr(NettyServer.COMPRESSION_TYPE).set(negotiatedCompressionType);
            } else {
                // Old client (v1) - consume the 4-byte magic packet
                in.skipBytes(4);

                // Send old-style acknowledgment (just magic)
                ByteBuf response = ctx.alloc().buffer(4);
                response.writeInt(MAGIC_AMOK);
                ctx.writeAndFlush(response);

                negotiatedVersion = PROTOCOL_VERSION_1;

                // Store v1 values in channel attributes
                ctx.pipeline().channel().attr(NettyServer.PROTOCOL_VERSION).set(negotiatedVersion);
                ctx.pipeline().channel().attr(NettyServer.COMPRESSION_TYPE).set(negotiatedCompressionType);
            }

            // Remove all existing handlers from the pipeline
            var handlers = ctx.pipeline().toMap();
            handlers.forEach((name, handler) -> ctx.pipeline().remove(handler));

            LOGGER.debug("New connection, negotiated version: {}, negotiated compression: {}", negotiatedVersion, negotiatedCompressionType);

            setupPipeline(ctx, negotiatedCompressionType);
        } else if (sslCtx == null || serverConfig.bindPort != -1) {
            // However if there's no magic packet and we don't use internal TLS or we are hosting on a separate port, we have to try to connect anyway, for use with reverse proxy setups
            // Default to v1 for reverse proxy setups
            ctx.pipeline().channel().attr(NettyServer.PROTOCOL_VERSION).set(PROTOCOL_VERSION_1);
            ctx.pipeline().channel().attr(NettyServer.COMPRESSION_TYPE).set(COMPRESSION_ZSTD);
            setupPipeline(ctx, COMPRESSION_ZSTD);
        }

        // Always remove this handler after processing if its still there
        if (ctx.pipeline().get(this.getClass()) != null) {
            ctx.pipeline().remove(this);
        }
    }

    private void setupPipeline(ChannelHandlerContext ctx, byte compressionType) {
        // add error handler pipeline
        ctx.pipeline().addLast("error-printer-first", new ErrorPrinter());
        ctx.pipeline().addLast("traffic-shaper", TrafficShaper.trafficShaper.getTrafficShapingHandler());
        if (sslCtx != null) { // If SSL context is provided, add TLS handler
            ctx.pipeline().addLast("tls", sslCtx.newHandler(ctx.alloc()));
        }

        // get the compression codec
        CompressionCodec codec = CompressionFactory.getCodec(compressionType);

        ctx.pipeline() // Add the rest
            .addLast("compression-encoder", new CompressionEncoder(codec))
            .addLast("compression-decoder", new CompressionDecoder(codec))
            .addLast("chunked-write", new ChunkedWriteHandler())
            .addLast("protocol-msg-decoder", new ProtocolMessageDecoder())
            .addLast("msg-handler", new ServerMessageHandler())
            .addLast("error-printer-last", new ErrorPrinter());
    }
}
