package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.net.InetSocketAddress;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.util.ReferenceCountUtil;

import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.detectors.HAProxyDetector;
import pl.skidam.automodpack_core.protocol.netty.detectors.MatchResult;

/**
 * Consumes the optional PROXY protocol header on the dedicated listener and feeds the claimed source address into
 * {@link NettyServer#REAL_REMOTE_ADDR}; a first read that is not a PROXY header passes through untouched.
 */
public class ProxyProtocolHandler extends ByteToMessageDecoder {

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		MatchResult result = HAProxyDetector.check(in);
		if (result == MatchResult.PARTIAL) return;
		if (result == MatchResult.MISMATCH) {
			out.add(in.readRetainedSlice(in.readableBytes()));
			ctx.pipeline().remove(this);
			return;
		}

		HAProxyDetector.DecodeResult decodeResult = HAProxyDetector.decode(in);
		if (decodeResult == null) return;
		if (decodeResult.message() == null) {
			ctx.close();
			return;
		}

		HAProxyMessage message = decodeResult.message();
		try {
			in.skipBytes(decodeResult.consumedBytes());
			if (message.sourceAddress() != null) {
				InetSocketAddress remoteAddress = new InetSocketAddress(message.sourceAddress(), message.sourcePort());
				ctx.channel().attr(NettyServer.REAL_REMOTE_ADDR).set(remoteAddress);
				LOGGER.debug("PROXY: Remote address set to {}", remoteAddress);
			}
		} catch (Exception e) {
			LOGGER.error("Error processing HAProxy message", e);
		} finally {
			ReferenceCountUtil.release(message);
		}
		ctx.pipeline().remove(this);
	}
}
