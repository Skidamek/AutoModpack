package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.GlobalVariables.*;
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

    private static final byte[] MAGIC_AMMH_ARRAY = {
            (byte) (MAGIC_AMMH >>> 24),
            (byte) (MAGIC_AMMH >>> 16),
            (byte) (MAGIC_AMMH >>> 8),
            (byte) MAGIC_AMMH
    };

    private final SslContext sslCtx;

    public ProtocolServerHandler(SslContext sslCtx) {
        this.sslCtx = sslCtx;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        boolean isSharedPort = (serverConfig.bindPort == -1 && hostServer.isRunning());
        int readerIndex = in.readerIndex();
        int readableBytes = in.readableBytes();

        for (int i = 0; i < MAGIC_AMMH_ARRAY.length; i++) {
            if (readableBytes <= i) return;

            if (in.getByte(readerIndex + i) != MAGIC_AMMH_ARRAY[i]) {
                if (isSharedPort) { // AutoModpack shares port with Minecraft, no magic packet detected, pass to the Minecraft pipeline
                    // Reset the reader index so we have the full message available. Technically, the reader shouldn't move anyway... But just to be sure, let's reset it for sanity.
                    in.readerIndex(readerIndex);
                } else { // Magic packet is not there, but it's a dedicated host, pass to the AutoModpack pipeline anyway
                    setupPipeline(ctx);
                }

                // Our job here is done, this handler won't be needed anymore for this connection
                detachHandler(ctx, in);
                return;
            }
        }

        handleMagicPacket(ctx, in);
    }

    private void detachHandler(ChannelHandlerContext ctx, ByteBuf in) {
        // Detach this handler from the pipeline
        // We must extract BEFORE removing to ensure we don't lose data if it wasn't buffered internally yet.
        ByteBuf payload = in.readRetainedSlice(in.readableBytes());
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(payload);
    }

    private void handleMagicPacket(ChannelHandlerContext ctx, ByteBuf in) {
        if (in.readableBytes() < 6) return;

        in.markReaderIndex();
        in.skipBytes(4);
        short hostnameLength = in.readShort();
        in.resetReaderIndex();

        if (in.readableBytes() < 4 + 2 + hostnameLength) return;

        in.skipBytes(6);
        byte[] hostnameBytes = new byte[hostnameLength];
        in.readBytes(hostnameBytes);
        String hostname = new String(hostnameBytes, StandardCharsets.UTF_8);

        LOGGER.debug("Received AMMH handshake. Hostname: {}", hostname);
        finalizeHandshake(ctx);
    }

    private void finalizeHandshake(ChannelHandlerContext ctx) {
        sendAck(ctx);
        ctx.pipeline().toMap().forEach((k, v) -> ctx.pipeline().remove(v));
        setupPipeline(ctx);
    }

    private void sendAck(ChannelHandlerContext ctx) {
        ByteBuf response = ctx.alloc().buffer(4);
        response.writeInt(MAGIC_AMOK);
        ctx.writeAndFlush(response);
    }

    private void setupPipeline(ChannelHandlerContext ctx) {
        ctx.pipeline().addLast("error-printer-first", new ErrorPrinter());
        ctx.pipeline().addLast("traffic-shaper", TrafficShaper.trafficShaper.getTrafficShapingHandler());

        if (sslCtx != null) {
            ctx.pipeline().addLast("tls", sslCtx.newHandler(ctx.alloc()));
            LOGGER.debug("Pipeline: TLS Enabled");
        } else {
            LOGGER.debug("Pipeline: TLS Disabled");
        }

        ctx.channel().attr(NettyServer.PROTOCOL_VERSION).set(PROTOCOL_VERSION);
        ctx.channel().attr(NettyServer.COMPRESSION_TYPE).set(COMPRESSION_ZSTD);
        ctx.channel().attr(NettyServer.CHUNK_SIZE).set(DEFAULT_CHUNK_SIZE);

        ctx.pipeline()
                .addLast("configuration-handler", new ConfigurationHandler())
                .addLast("compression-encoder", new CompressionEncoder())
                .addLast("compression-decoder", new CompressionDecoder())
                .addLast("chunked-write", new ChunkedWriteHandler())
                .addLast("protocol-msg-decoder", new ProtocolMessageDecoder())
                .addLast("msg-handler", new ServerMessageHandler())
                .addLast("error-printer-last", new ErrorPrinter());
    }
}