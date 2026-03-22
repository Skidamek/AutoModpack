package pl.skidam.automodpack_core.protocol.netty.handler;

import dev.iroh.IrohPeer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.util.ReferenceCountUtil;
import pl.skidam.automodpack_core.protocol.HybridHostServer;
import pl.skidam.automodpack_core.protocol.netty.detectors.AMMHDetector;
import pl.skidam.automodpack_core.protocol.netty.detectors.HAProxyDetector;
import pl.skidam.automodpack_core.protocol.netty.detectors.MatchResult;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.hostServer;
import static pl.skidam.automodpack_core.Constants.serverConfig;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMID;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMOK;

public class ProtocolServerHandler extends ByteToMessageDecoder {
    private static final int AMID_FRAME_LENGTH = 4 + 32;

    private enum Stage {
        DETECT_BOOTSTRAP,
        WAIT_FOR_AMID,
        PIPE_INSTALLED
    }

    private boolean proxyCheckFinished = false;
    private SocketAddress remoteAddress;
    private ByteBuf originalBuffer;
    private Stage stage = Stage.DETECT_BOOTSTRAP;

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

        if (stage == Stage.WAIT_FOR_AMID) {
            handleAmid(ctx, in);
            return;
        }
        if (stage == Stage.PIPE_INSTALLED) {
            return;
        }

        MatchResult result = AMMHDetector.check(in);
        if (result == MatchResult.PARTIAL) {
            return;
        }
        if (result == MatchResult.MISMATCH) {
            if (isSharedMinecraftPort()) {
                fallbackToOriginalPipeline(ctx, in, out);
            } else {
                safeReleaseOriginalBuffer();
                ctx.close();
            }
            return;
        }

        AMMHDetector.DecodeResult decodeResult = AMMHDetector.decode(in);
        if (decodeResult == null) {
            return;
        }
        if (decodeResult.hostname() == null) {
            safeReleaseOriginalBuffer();
            ctx.close();
            return;
        }

        onBootstrapMatch(ctx, in, decodeResult);
    }

    private MatchResult handleProxyCheck(ChannelHandlerContext ctx, ByteBuf in) {
        MatchResult result = HAProxyDetector.check(in);
        if (result != MatchResult.MATCHED) {
            return result;
        }

        HAProxyDetector.DecodeResult decodeResult = HAProxyDetector.decode(in);
        if (decodeResult == null) {
            return MatchResult.PARTIAL;
        }
        if (decodeResult.message() == null) {
            return MatchResult.MISMATCH;
        }

        onProxyMatch(ctx, in, decodeResult);
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

    private void onBootstrapMatch(ChannelHandlerContext ctx, ByteBuf in, AMMHDetector.DecodeResult result) {
        if (!(hostServer instanceof HybridHostServer hybridHostServer) || !hybridHostServer.isIrohEnabled()) {
            LOGGER.warn("Rejecting AMMH bootstrap on {} because the iroh runtime is not available", channelLabel(ctx));
            safeReleaseOriginalBuffer();
            ctx.close();
            return;
        }

        in.skipBytes(result.consumedBytes());
        safeReleaseOriginalBuffer();
        stage = Stage.WAIT_FOR_AMID;
        LOGGER.info("Accepted AMMH bootstrap from {} on {} host={}", remoteAddress, channelLabel(ctx), result.hostname());
        ctx.writeAndFlush(ctx.alloc().buffer(4).writeInt(MAGIC_AMOK)).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                LOGGER.warn("Failed to acknowledge AMMH bootstrap on {}", channelLabel(ctx), future.cause());
                ctx.close();
            }
        });
    }

    private void handleAmid(ChannelHandlerContext ctx, ByteBuf in) {
        if (in.readableBytes() < AMID_FRAME_LENGTH) {
            return;
        }

        int start = in.readerIndex();
        if (in.getInt(start) != MAGIC_AMID) {
            LOGGER.warn("Rejecting raw iroh bootstrap from {} on {}: invalid AMID prelude", remoteAddress, channelLabel(ctx));
            ctx.close();
            return;
        }

        byte[] endpointId = new byte[32];
        in.getBytes(start + 4, endpointId);

        if (!(hostServer instanceof HybridHostServer hybridHostServer) || !hybridHostServer.isIrohEnabled()) {
            ctx.close();
            return;
        }
        if (!hybridHostServer.isEndpointAuthorized(endpointId)) {
            LOGGER.warn("Rejecting raw iroh bootstrap from {} on {}: endpoint is not authorized", remoteAddress, channelLabel(ctx));
            ctx.close();
            return;
        }

        IrohPeer peer = hybridHostServer.bootstrapIrohPeer(ctx.channel(), endpointId);
        if (peer == null) {
            LOGGER.warn("Rejecting raw iroh bootstrap from {} on {}: failed to register peer", remoteAddress, channelLabel(ctx));
            ctx.close();
            return;
        }

        in.skipBytes(AMID_FRAME_LENGTH);
        ByteBuf remaining = in.isReadable() ? in.readRetainedSlice(in.readableBytes()) : null;
        setupPipePipeline(ctx, peer);
        stage = Stage.PIPE_INSTALLED;
        if (ctx.pipeline().context(this) != null) {
            ctx.pipeline().remove(this);
        }
        if (remaining != null) {
            ctx.fireChannelRead(remaining);
        }
        LOGGER.info("Accepted AMID bootstrap from {} on {} for endpoint {}", remoteAddress, channelLabel(ctx), shortPeerId(endpointId));
    }

    private void fallbackToOriginalPipeline(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        ByteBuf payload = reconstructPayload(ctx, in);
        out.add(payload);
        if (ctx.pipeline().context(this) != null) {
            ctx.pipeline().remove(this);
        }
    }

    private ByteBuf reconstructPayload(ChannelHandlerContext ctx, ByteBuf in) {
        if (originalBuffer == null) {
            return in.readRetainedSlice(in.readableBytes());
        }

        CompositeByteBuf composite = ctx.alloc().compositeBuffer();
        composite.addComponent(true, originalBuffer);
        originalBuffer = null;
        composite.addComponent(true, in.readRetainedSlice(in.readableBytes()));
        return composite;
    }

    private void appendConsumedBytes(ChannelHandlerContext ctx, ByteBuf consumed) {
        if (originalBuffer == null) {
            originalBuffer = consumed;
            return;
        }

        if (originalBuffer instanceof CompositeByteBuf composite) {
            composite.addComponent(true, consumed);
        } else {
            CompositeByteBuf composite = ctx.alloc().compositeBuffer();
            composite.addComponent(true, originalBuffer);
            composite.addComponent(true, consumed);
            originalBuffer = composite;
        }
    }

    private void safeReleaseOriginalBuffer() {
        if (originalBuffer != null && originalBuffer.refCnt() > 0) {
            originalBuffer.release();
        }
        originalBuffer = null;
    }

    private void setupPipePipeline(ChannelHandlerContext ctx, IrohPeer peer) {
        ctx.pipeline().toMap().forEach((name, handler) -> {
            if (handler != this) {
                ctx.pipeline().remove(handler);
            }
        });

        ctx.pipeline()
            .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4))
            .addLast("framePrepender", new LengthFieldPrepender(4))
            .addLast("irohPipe", new dev.iroh.IrohPipeHandler(peer))
            .addLast("error-printer-last", new ErrorPrinter());
    }

    private static boolean isSharedMinecraftPort() {
        return serverConfig.bindPort == -1 && hostServer != null && hostServer.isRunning();
    }

    private static String shortPeerId(byte[] endpointId) {
        if (endpointId == null || endpointId.length == 0) {
            return "unknown";
        }

        StringBuilder builder = new StringBuilder(Math.min(endpointId.length, 8) * 2);
        for (int i = 0; i < Math.min(endpointId.length, 8); i++) {
            builder.append(String.format("%02x", endpointId[i]));
        }
        return builder.append("...").toString();
    }

    private static String channelLabel(ChannelHandlerContext ctx) {
        return ctx.channel().id().asShortText();
    }
}
