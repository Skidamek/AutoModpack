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

        // TODO: make chunk size negotiable
        int magic = in.getInt(0);
        if (magic == MAGIC_AMMC) {
            // Server should always support AMMC protocol (magic packets) (preferred way to connect, required for hosting on Minecraft port and good for backwards compatibility)
            byte negotiatedProtocolVersion;
            byte negotiatedCompressionType;

            LOGGER.debug("Received AMMC magic packet from client, readable bytes: {}", in.readableBytes());

            // Check if client sent protocol version (v2+ clients send 5 bytes: magic + version)
            if (in.readableBytes() >= 6) {
                in.skipBytes(4); // Skip magic
                byte clientProtocolVersion = in.readByte();
                byte clientCompressionType = in.readByte();

                LOGGER.debug("Client protocol version: {}, Client compression type: {}", clientProtocolVersion, clientCompressionType);

                // Negotiate protocol version (prefer v2 if both support it)
                negotiatedProtocolVersion = (clientProtocolVersion >= PROTOCOL_VERSION_2) ? PROTOCOL_VERSION_2 : PROTOCOL_VERSION_1;
                
                // Check if we support the client's compression type
                if (PlatformUtils.isAndroid() && clientCompressionType == COMPRESSION_ZSTD) { // Zstd unsupported on Android
                    negotiatedCompressionType = COMPRESSION_GZIP; // Default to Gzip on Android
                } else if (clientCompressionType == COMPRESSION_ZSTD || clientCompressionType == COMPRESSION_GZIP || clientCompressionType == COMPRESSION_NONE) {
                    negotiatedCompressionType = clientCompressionType;
                } else {
                    negotiatedCompressionType = COMPRESSION_ZSTD; // Default to Zstd if unsupported
                }

                // Send acknowledgment with negotiated version and compression type
                ByteBuf response = ctx.alloc().buffer(6);
                response.writeInt(MAGIC_AMOK);
                response.writeByte(negotiatedProtocolVersion);
                response.writeByte(negotiatedCompressionType);
                ctx.writeAndFlush(response);

                LOGGER.debug("Negotiated protocol version: {}, Negotiated compression type: {}", negotiatedProtocolVersion, negotiatedCompressionType);
            } else {
                // Old client (v1) - consume the 4-byte magic packet
                in.skipBytes(4);

                negotiatedProtocolVersion = PROTOCOL_VERSION_1;
                negotiatedCompressionType = COMPRESSION_ZSTD;

                // Send old-style acknowledgment (just magic)
                ByteBuf response = ctx.alloc().buffer(4);
                response.writeInt(MAGIC_AMOK);
                ctx.writeAndFlush(response);

                LOGGER.debug("Old v1 client detected, defaulting to protocol version: {}, compression type: {}", negotiatedProtocolVersion, negotiatedCompressionType);
            }

            // Store negotiated values in channel attributes
            ctx.pipeline().channel().attr(NettyServer.PROTOCOL_VERSION).set(negotiatedProtocolVersion);
            ctx.pipeline().channel().attr(NettyServer.COMPRESSION_TYPE).set(negotiatedCompressionType);

            // Remove all existing handlers from the pipeline
            var handlers = ctx.pipeline().toMap();
            handlers.forEach((name, handler) -> ctx.pipeline().remove(handler));

            LOGGER.debug("New connection, negotiated version: {}, negotiated compression: {}", negotiatedProtocolVersion, negotiatedCompressionType);
            setupPipeline(ctx, negotiatedCompressionType);
        } else if (sslCtx == null || serverConfig.bindPort != -1) {
            // However if there's no magic packet and we don't use internal TLS or we are hosting on a separate port, we have to try to connect anyway, for use with reverse proxy setups
            // Default to v1 for reverse proxy setups
            ctx.pipeline().channel().attr(NettyServer.PROTOCOL_VERSION).set(PROTOCOL_VERSION_1);
            ctx.pipeline().channel().attr(NettyServer.COMPRESSION_TYPE).set(COMPRESSION_ZSTD);

            LOGGER.debug("No AMMC magic packet received, defaulting to protocol version: {}, compression type: {}", PROTOCOL_VERSION_1, COMPRESSION_ZSTD);
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
            LOGGER.debug("Added TLS handler to the pipeline");
        } else {
            LOGGER.debug("No TLS handler added to the pipeline");
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
