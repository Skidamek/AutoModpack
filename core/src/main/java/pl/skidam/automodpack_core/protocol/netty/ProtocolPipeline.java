package pl.skidam.automodpack_core.protocol.netty;

import java.net.SocketAddress;

import io.netty.channel.Channel;
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

/** Installs the common post-handshake transfer pipeline for every transport. */
public final class ProtocolPipeline {
	private ProtocolPipeline() {}

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
