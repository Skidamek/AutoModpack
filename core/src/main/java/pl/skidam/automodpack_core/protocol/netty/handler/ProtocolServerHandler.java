package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.ProtocolPipeline;
import pl.skidam.automodpack_core.protocol.netty.TrafficShaper;
import pl.skidam.automodpack_core.protocol.netty.detectors.AMMHDetector;
import pl.skidam.automodpack_core.protocol.netty.detectors.HAProxyDetector;
import pl.skidam.automodpack_core.protocol.netty.detectors.MatchResult;

public class ProtocolServerHandler extends ByteToMessageDecoder {

	private final NettyServer server;
	private final SslContext sslCtx;
	private final ModpackConnectionMode connectionMode;
	private final boolean sharedMinecraftSocket;
	private boolean proxyCheckFinished;
	private SocketAddress remoteAddress;

	public ProtocolServerHandler(NettyServer server, ModpackConnectionMode connectionMode, boolean sharedMinecraftSocket) {
		if (connectionMode == ModpackConnectionMode.HOLEPUNCH) throw new IllegalArgumentException("HOLEPUNCH does not use ProtocolServerHandler");
		if (sharedMinecraftSocket && connectionMode != ModpackConnectionMode.MAGIC_PACKET) {
			throw new IllegalArgumentException("Only MAGIC_PACKET can use a shared Minecraft socket");
		}

		this.server = server;
		this.sslCtx = server.getSslCtx();
		this.connectionMode = connectionMode;
		this.sharedMinecraftSocket = sharedMinecraftSocket;
		this.proxyCheckFinished = sharedMinecraftSocket;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		remoteAddress = ctx.channel().remoteAddress();
		super.channelActive(ctx);
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		if (!proxyCheckFinished) {
			MatchResult result = handleProxyCheck(ctx, in);
			if (result == MatchResult.PARTIAL || !ctx.channel().isActive()) return;
			proxyCheckFinished = true;
		}

		if (connectionMode == ModpackConnectionMode.DIRECT) {
			finalizeHandshake(ctx);
			return;
		}

		handleMagicCheck(ctx, in, out);
	}

	private MatchResult handleProxyCheck(ChannelHandlerContext ctx, ByteBuf in) {
		MatchResult result = HAProxyDetector.check(in);
		if (result != MatchResult.MATCHED) return result;

		HAProxyDetector.DecodeResult decodeResult = HAProxyDetector.decode(in);
		if (decodeResult == null) return MatchResult.PARTIAL;
		if (decodeResult.message() == null) {
			ctx.close();
			return MatchResult.MATCHED;
		}

		onProxyMatch(in, decodeResult);
		return MatchResult.MATCHED;
	}

	private void handleMagicCheck(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		MatchResult result = AMMHDetector.check(in);
		if (result == MatchResult.PARTIAL) return;
		if (result == MatchResult.MISMATCH) {
			onMagicMismatch(ctx, in, out);
			return;
		}

		AMMHDetector.DecodeResult decodeResult = AMMHDetector.decode(in);
		if (decodeResult == null) return;
		if (decodeResult.hostname() == null) {
			onMagicMismatch(ctx, in, out);
			return;
		}

		onMagicMatch(ctx, in, decodeResult);
	}

	private void onProxyMatch(ByteBuf in, HAProxyDetector.DecodeResult result) {
		HAProxyMessage message = result.message();
		try {
			in.skipBytes(result.consumedBytes());
			if (message.sourceAddress() != null) {
				remoteAddress = new InetSocketAddress(message.sourceAddress(), message.sourcePort());
				LOGGER.debug("PROXY: Remote address set to {}", remoteAddress);
			}
		} catch (Exception e) {
			LOGGER.error("Error processing HAProxy message", e);
		} finally {
			ReferenceCountUtil.release(message);
		}
	}

	private void onMagicMatch(ChannelHandlerContext ctx, ByteBuf in, AMMHDetector.DecodeResult result) {
		in.skipBytes(result.consumedBytes());
		LOGGER.debug("AMMH Handshake: {}", result.hostname());
		ctx.writeAndFlush(ctx.alloc().buffer(4).writeInt(MAGIC_AMOK));
		finalizeHandshake(ctx);
	}

	private void onMagicMismatch(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		if (sharedMinecraftSocket) {
			out.add(in.readRetainedSlice(in.readableBytes()));
			ctx.pipeline().remove(this);
		} else {
			ctx.close();
		}
	}

	private void finalizeHandshake(ChannelHandlerContext ctx) {
		ctx.pipeline().toMap().forEach((name, handler) -> {
			if (handler != this) ctx.pipeline().remove(handler);
		});

		setupPipeline(ctx);
		if (ctx.pipeline().context(this) != null) ctx.pipeline().remove(this);
	}

	private void setupPipeline(ChannelHandlerContext ctx) {
		ctx.pipeline().addLast("error-printer-first", new ErrorPrinter());
		ctx.pipeline().addLast("traffic-shaper", TrafficShaper.trafficShaper.getTrafficShapingHandler());

		if (sslCtx != null) {
			ctx.pipeline().addLast("tls", sslCtx.newHandler(ctx.alloc()));
			LOGGER.debug("Pipeline: TLS Enabled");
		} else {
			LOGGER.debug("Pipeline: TLS termination handled externally");
		}

		ProtocolPipeline.install(ctx.channel(), server, remoteAddress);
	}
}
