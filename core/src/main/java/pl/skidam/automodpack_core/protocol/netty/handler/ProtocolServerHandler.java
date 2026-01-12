package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import pl.skidam.automodpack_core.protocol.netty.HAProxyDetector;
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
    private boolean proxyCheckFinished = false;

    public ProtocolServerHandler(SslContext sslCtx) {
        this.sslCtx = sslCtx;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int originalReaderIndex = in.readerIndex();
        int readableBytes = in.readableBytes();

        // (optional) HAProxy PROXY Protocol detection to retrieve the REAL_REMOTE_ADDR
        if (!proxyCheckFinished) {
            HAProxyDetector.MatchResult result = HAProxyDetector.check(in, originalReaderIndex, readableBytes);
            if (result == HAProxyDetector.MatchResult.MATCHED) {
                HAProxyMessage msg = HAProxyDetector.decodeAndAdvance(in);
                handleProxyMessage(ctx, msg);
                proxyCheckFinished = true;
            } else if (result == HAProxyDetector.MatchResult.MISMATCH) {
                handleProxyMessage(ctx, null);
                proxyCheckFinished = true;
            } else {
                return; // Partial match, wait for more
            }
        }

        // AMMH Magic Detection
        boolean isSharedPort = (serverConfig.bindPort == -1 && hostServer.isRunning());
        int ammhReaderIndex = in.readerIndex();
        int ammhReadable = in.readableBytes();

        for (int i = 0; i < MAGIC_AMMH_ARRAY.length; i++) {
            if (ammhReadable <= i) return; // Partial match, wait for more

            if (in.getByte(ammhReaderIndex + i) != MAGIC_AMMH_ARRAY[i]) {
                if (isSharedPort) { // AutoModpack shares port with Minecraft, no magic packet detected, pass to the Minecraft pipeline
                    // Reset the reader index so we have the full message available.
                    in.readerIndex(originalReaderIndex);
                } else { // Magic packet is not there, but it's a dedicated host, pass to the AutoModpack pipeline anyway
                    setupPipeline(ctx);
                }

                // Our job here is done, this handler won't be needed anymore for this connection
                detach(ctx, in);
                return;
            }
        }

        handleMagicPacket(ctx, in);
    }

    private void handleProxyMessage(ChannelHandlerContext ctx, HAProxyMessage msg) {
        try {
            if (msg == null || msg.sourceAddress() == null) {
                ctx.channel().attr(NettyServer.REAL_REMOTE_ADDR).set(ctx.channel().remoteAddress());
                LOGGER.debug("No PROXY protocol detected, using remote address as REAL_REMOTE_ADDR");
            } else {
                InetSocketAddress realAddress = new InetSocketAddress(msg.sourceAddress(), msg.sourcePort());
                ctx.channel().attr(NettyServer.REAL_REMOTE_ADDR).set(realAddress);
                LOGGER.debug("Detected PROXY protocol, using source address {} as REAL_REMOTE_ADDR", realAddress);
            }
        } finally {
            if (msg != null) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private void detach(ChannelHandlerContext ctx, ByteBuf in) {
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