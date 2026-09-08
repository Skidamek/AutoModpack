package pl.skidam.automodpack_core.protocol.netty;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.handler.CompressionDecoder;
import pl.skidam.automodpack_core.protocol.netty.handler.CompressionEncoder;
import pl.skidam.automodpack_core.protocol.netty.handler.ConfigurationHandler;
import pl.skidam.automodpack_core.protocol.netty.handler.ErrorPrinter;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolMessageDecoder;
import pl.skidam.automodpack_core.protocol.netty.handler.ServerMessageHandler;

/** Installs the shared server pipeline for every transport: handshake framing, TLS, then the post-handshake transfer. */
public final class ProtocolPipeline {
	private ProtocolPipeline() {}

	/**
	 * Installs the server transfer pipeline: error printer, traffic shaper, any wire-side handlers, TLS, then the post-handshake pipeline. Wire-side handlers sit between the shaper and
	 * TLS, on the wire side of it. Returns the TLS handler, or null when TLS termination is handled externally.
	 */
	public static SslHandler installServer(Channel channel, NettyServer server, SocketAddress remoteAddress, ChannelHandler... wireSideHandlers) {
		ChannelPipeline pipeline = channel.pipeline();
		pipeline.addLast("error-printer-first", new ErrorPrinter());
		pipeline.addLast("traffic-shaper", TrafficShaper.handler());
		for (ChannelHandler wireSideHandler : wireSideHandlers) {
			pipeline.addLast(wireSideHandler);
		}
		SslHandler sslHandler = server.getSslCtx() == null ? null : server.getSslCtx().newHandler(channel.alloc());
		if (sslHandler != null) {
			pipeline.addLast("tls", sslHandler);
		} else {
			LOGGER.debug("TLS termination handled externally: {}", remoteAddress);
		}
		install(channel, server, remoteAddress);
		return sslHandler;
	}

	public static void install(Channel channel, NettyServer server, SocketAddress remoteAddress) {
		channel.attr(NettyServer.REAL_REMOTE_ADDR).set(remoteAddress);
		channel.attr(NettyServer.PROTOCOL_VERSION).set(NetUtils.LATEST_SUPPORTED_PROTOCOL_VERSION);
		CompressionType defaultCompression = CompressionFactory.isAvailable(CompressionType.ZSTD) ? CompressionType.ZSTD : CompressionType.GZIP;
		channel.attr(NettyServer.COMPRESSION_TYPE).set(defaultCompression);
		channel.attr(NettyServer.CHUNK_SIZE).set(NetUtils.DEFAULT_CHUNK_SIZE);

		channel.pipeline().addLast("configuration-handler", new ConfigurationHandler()).addLast("compression-encoder", new CompressionEncoder())
				.addLast("compression-decoder", new CompressionDecoder()).addLast("chunked-write", new ChunkedWriteHandler())
				.addLast("protocol-msg-decoder", new ProtocolMessageDecoder()).addLast("msg-handler", new ServerMessageHandler(server))
				.addLast("error-printer-last", new ErrorPrinter());
	}
}
