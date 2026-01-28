package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.TrafficShaper;
import pl.skidam.automodpack_core.protocol.netty.detectors.AMMHDetector;
import pl.skidam.automodpack_core.protocol.netty.detectors.HAProxyDetector;
import pl.skidam.automodpack_core.protocol.netty.detectors.MatchResult;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.hostServer;
import static pl.skidam.automodpack_core.Constants.serverConfig;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

public class ProtocolServerHandler extends ByteToMessageDecoder {

    private final SslContext sslCtx;
    private boolean proxyCheckFinished = false;
    private boolean magicCheckFinished = false;
    private SocketAddress remoteAddress = null;
    private ByteBuf originalBuffer = null;

    public ProtocolServerHandler(SslContext sslCtx) {
        this.sslCtx = sslCtx;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.remoteAddress = ctx.channel().remoteAddress();
        super.channelActive(ctx);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!proxyCheckFinished) {
            MatchResult res = handleProxyCheck(ctx, in);
            if (res == MatchResult.PARTIAL) {
                return;
            }
            proxyCheckFinished = true;
        }

        if (!magicCheckFinished) {
            MatchResult res = handleMagicCheck(ctx, in, out);
            if (res == MatchResult.PARTIAL) {
                return;
            }
            magicCheckFinished = true;
        }
    }

    private MatchResult handleProxyCheck(ChannelHandlerContext ctx, ByteBuf in) {
        MatchResult result = HAProxyDetector.check(in);
        if (result != MatchResult.MATCHED) {
            return result;
        }

        HAProxyDetector.DecodeResult decodeResult = HAProxyDetector.decode(in);
        if (decodeResult == null) return MatchResult.PARTIAL;
        if (decodeResult.message() == null) return MatchResult.MISMATCH;

        onProxyMatch(ctx, in, decodeResult);
        return MatchResult.MATCHED;
    }

    private MatchResult handleMagicCheck(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        MatchResult result = AMMHDetector.check(in);
        if (result != MatchResult.MATCHED) {
            if (result == MatchResult.MISMATCH) onMagicMismatch(ctx, in, out);
            return result;
        }

        AMMHDetector.DecodeResult decodeResult = AMMHDetector.decode(in);
        if (decodeResult == null) return MatchResult.PARTIAL;
        if (decodeResult.hostname() == null) return MatchResult.MISMATCH;

        onMagicMatch(ctx, in, decodeResult);
        return MatchResult.MATCHED;
    }

    private void onProxyMatch(ChannelHandlerContext ctx, ByteBuf in, HAProxyDetector.DecodeResult result) {
        HAProxyMessage msg = result.message();
        try {
            appendConsumedBytes(ctx, in.readRetainedSlice(result.consumedBytes()));

            if (msg != null && msg.sourceAddress() != null) {
                remoteAddress = new InetSocketAddress(msg.sourceAddress(), msg.sourcePort());
                LOGGER.debug("PROXY: Remote address set to {}", remoteAddress);
            }
        } catch (Exception e) {
            LOGGER.error("Error processing HAProxy message", e);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void onMagicMatch(ChannelHandlerContext ctx, ByteBuf in, AMMHDetector.DecodeResult result) {
        in.skipBytes(result.consumedBytes());

        LOGGER.debug("AMMH Handshake: {}", result.hostname());
        ctx.writeAndFlush(ctx.alloc().buffer(4).writeInt(MAGIC_AMOK));

        finalizeHandshake(ctx);
    }

    private void onMagicMismatch(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        boolean isSharedPort = serverConfig.bindPort == -1 && hostServer.isRunning();
        if (isSharedPort) {
            fallbackToOriginalPipeline(ctx, in, out);
        } else {
            finalizeHandshake(ctx);
        }
    }

    private void fallbackToOriginalPipeline(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        ByteBuf payload = reconstructPayload(ctx, in);
        out.add(payload);
        ctx.pipeline().remove(this);
    }

    private ByteBuf reconstructPayload(ChannelHandlerContext ctx, ByteBuf in) {
        if (originalBuffer == null) {
            return in.readRetainedSlice(in.readableBytes());
        }

        CompositeByteBuf composite = ctx.alloc().compositeBuffer();
        // Add originalBuffer (Proxy header bytes)
        composite.addComponent(true, originalBuffer);

        // originalBuffer ownership is now with the Composite.
        // We null it out so we don't accidentally release it again in channelInactive.
        originalBuffer = null;

        // Add the current buffer content
        composite.addComponent(true, in.readRetainedSlice(in.readableBytes()));
        return composite;
    }

    private void appendConsumedBytes(ChannelHandlerContext ctx, ByteBuf consumed) {
        if (originalBuffer == null) {
            originalBuffer = consumed;
            return;
        }

        if (originalBuffer instanceof CompositeByteBuf) {
            ((CompositeByteBuf) originalBuffer).addComponent(true, consumed);
        } else {
            CompositeByteBuf composite = ctx.alloc().compositeBuffer();
            composite.addComponent(true, originalBuffer);
            composite.addComponent(true, consumed);
            originalBuffer = composite;
        }
    }

    private void finalizeHandshake(ChannelHandlerContext ctx) {
        ctx.pipeline().toMap().forEach((k, v) -> {
            if (v == this) return;
            ctx.pipeline().remove(v);
        });

        safeReleaseOriginalBuffer();
        setupPipeline(ctx);

        if (ctx.pipeline().context(this) != null) {
            ctx.pipeline().remove(this);
        }
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

        ctx.channel().attr(NettyServer.REAL_REMOTE_ADDR).set(remoteAddress);
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

    private void safeReleaseOriginalBuffer() {
        if (originalBuffer != null) {
            if (originalBuffer.refCnt() > 0) originalBuffer.release();
            originalBuffer = null;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        safeReleaseOriginalBuffer();
        super.channelInactive(ctx);
    }
}