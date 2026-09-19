package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.HTTP_IDLE_REAP_SECONDS;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMOK;

import java.util.List;
import java.util.concurrent.Executor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.timeout.IdleStateHandler;

import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.detectors.AMMHDetector;
import pl.skidam.automodpack_core.protocol.netty.detectors.MatchResult;

/**
 * The pre-TLS AMMH gate for MAGIC hosting. On a dedicated socket any non-magic first read is closed; on the shared
 * Minecraft socket the gate watches the plaintext bytes and vanilla traffic passes untouched, while an AMMH match
 * swaps the channel over to the shaper, TLS and the URL contract. The post-magic bytes replay into the new stack.
 */
public class AmmhGateHandler extends ByteToMessageDecoder {

	private final NettyServer server;
	private final Executor senders;
	private final boolean sharedMinecraftSocket;

	public AmmhGateHandler(NettyServer server, Executor senders, boolean sharedMinecraftSocket) {
		this.server = server;
		this.senders = senders;
		this.sharedMinecraftSocket = sharedMinecraftSocket;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		MatchResult result = AMMHDetector.check(in);
		if (result == MatchResult.PARTIAL) return;
		if (result == MatchResult.MISMATCH) {
			onMismatch(ctx, in, out);
			return;
		}

		AMMHDetector.DecodeResult decodeResult = AMMHDetector.decode(in);
		if (decodeResult == null) return;
		if (decodeResult.hostname() == null) {
			onMismatch(ctx, in, out);
			return;
		}

		in.skipBytes(decodeResult.consumedBytes());
		LOGGER.debug("AMMH Handshake: {}", decodeResult.hostname());
		ctx.writeAndFlush(ctx.alloc().buffer(4).writeInt(MAGIC_AMOK));
		// The post-magic bytes ride the decoder's output list, so they replay into the contract stack the removal installs.
		out.add(in.readRetainedSlice(in.readableBytes()));
		swapToContract(ctx);
	}

	private void onMismatch(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		if (!sharedMinecraftSocket) {
			ctx.close();
			return;
		}
		out.add(in.readRetainedSlice(in.readableBytes()));
		ctx.pipeline().remove(this);
	}

	private void swapToContract(ChannelHandlerContext ctx) {
		ChannelPipeline pipeline = ctx.pipeline();
		if (sharedMinecraftSocket) {
			pipeline.toMap().forEach((name, handler) -> {
				if (handler != this) pipeline.remove(handler);
			});
			pipeline.addLast(IdleStateHandler.class.getSimpleName(), new IdleStateHandler(0, 0, HTTP_IDLE_REAP_SECONDS));
			pipeline.addLast("traffic-shaper", server.trafficHandler());
		}
		server.installContractHandlers(pipeline);
		pipeline.remove(this);
	}
}
