package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.serverConfig;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.ssl.SslHandler;

import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.ProtocolPipeline;
import pl.skidam.mcholepunch.HolepunchConnection;
import pl.skidam.mcholepunch.server.netty.HolepunchChannelApplication;
import pl.skidam.mcholepunch.server.netty.NettyChannelRegistry;

/**
 * Bridges holepunch takeovers into the automodpack protocol: mcholepunch hands the taken-over
 * Minecraft channel over on its own event loop and this bridge installs the same TLS and
 * transfer pipeline the DIRECT listener uses, wrapped in the transport-era camouflage handlers.
 */
public final class ServerHolepunchBridge {
	private static final Set<Channel> channels = ConcurrentHashMap.newKeySet();
	private static NettyChannelRegistry.Registration registration;

	private ServerHolepunchBridge() {}

	public static synchronized void register(NettyServer server) {
		if (!serverConfig.modpackHost || serverConfig.connectionMode != ModpackConnectionMode.HOLEPUNCH || registration != null) return;
		registration = NettyChannelRegistry.register(maxPendingWriteBytes(), application(server));
	}

	public static synchronized boolean isRegistered() {
		return registration != null;
	}

	public static synchronized void close() {
		if (registration != null) {
			registration.close();
			registration = null;
		}
		for (Channel channel : channels) {
			channel.close();
		}
		channels.clear();
	}

	private static HolepunchChannelApplication application(NettyServer server) {
		return (channel, connection) -> install(server, channel, connection);
	}

	private static void install(NettyServer server, Channel channel, HolepunchConnection connection) throws Exception {
		SocketAddress remoteAddress = channel.remoteAddress();
		channels.add(channel);
		channel.closeFuture().addListener(future -> channels.remove(channel));
		// The camouflage pair is wire-side of TLS: inbound records decamouflage before TLS decrypts
		// them, outbound records camouflage after TLS encrypts them.
		SslHandler sslHandler = ProtocolPipeline.installServer(channel, server, remoteAddress, new CamouflageEncoder(connection), new CamouflageDecoder(connection));
		if (sslHandler != null) {
			sslHandler.handshakeFuture().addListener(future -> {
				if (future.isSuccess()) {
					connection.commitTransportUpgrade().exceptionally(error -> {
						LOGGER.debug("TLS record camouflage setup failed via holepunch: {}", remoteAddress, error);
						channel.close();
						return null;
					});
				} else {
					LOGGER.debug("TLS handshake failed via holepunch: {}", remoteAddress, future.cause());
				}
			});
		}
		LOGGER.debug("Holepunched AutoModpack connection handed over: {}", remoteAddress);
	}

	/** Forwards decrypted transport-frame payloads before the handoff and decodes camouflaged TLS records after it. */
	static final class CamouflageDecoder extends ByteToMessageDecoder {
		private final HolepunchConnection connection;
		private final TlsRecordCamouflage.Pair camouflage;

		CamouflageDecoder(HolepunchConnection connection) throws Exception {
			this.connection = connection;
			this.camouflage = TlsRecordCamouflage.create(connection.transportSecret(), false);
		}

		@Override
		protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) throws IOException {
			if (!connection.isRaw()) {
				output.add(input.readRetainedSlice(input.readableBytes()));
				return;
			}
			ByteBuf decoded = context.alloc().ioBuffer(input.readableBytes() + camouflage.inbound().pendingRecordLength());
			ByteBuffer view = decoded.nioBuffer(0, decoded.capacity());
			camouflage.inbound().decode(readableView(input), view);
			if (view.position() == 0) {
				decoded.release();
				return;
			}
			decoded.writerIndex(view.position());
			output.add(decoded);
		}
	}

	/** Passes handshake-era records through and encodes post-handoff records as camouflage frames the peer's pipeline decodes. */
	static final class CamouflageEncoder extends MessageToMessageEncoder<ByteBuf> {
		private final HolepunchConnection connection;
		private final TlsRecordCamouflage.Pair camouflage;

		CamouflageEncoder(HolepunchConnection connection) throws Exception {
			this.connection = connection;
			this.camouflage = TlsRecordCamouflage.create(connection.transportSecret(), false);
		}

		@Override
		protected void encode(ChannelHandlerContext context, ByteBuf message, List<Object> output) throws IOException {
			if (!connection.isRaw()) {
				output.add(message.retain());
				return;
			}
			// The SslHandler writes one TLS record per message, so one frame header of expansion
			// plus whatever partial record the encoder still holds is the whole bound.
			ByteBuf encoded = context.alloc().ioBuffer(message.readableBytes() + camouflage.outbound().pendingRecordLength() + TlsRecordCamouflage.FRAME_HEADER_LENGTH);
			ByteBuffer view = encoded.nioBuffer(0, encoded.capacity());
			camouflage.outbound().encode(readableView(message), view);
			if (view.position() == 0) {
				encoded.release();
				return;
			}
			encoded.writerIndex(view.position());
			output.add(encoded);
		}
	}

	/** A readable view of the buffer for the camouflage codec, zero-copy when the buffer exposes one backing NIO buffer. */
	private static ByteBuffer readableView(ByteBuf buffer) {
		if (buffer.nioBufferCount() == 1) {
			ByteBuffer view = buffer.nioBuffer();
			buffer.skipBytes(buffer.readableBytes());
			return view;
		}
		byte[] bytes = new byte[buffer.readableBytes()];
		buffer.readBytes(bytes);
		return ByteBuffer.wrap(bytes);
	}

	private static long maxPendingWriteBytes() {
		long maxCompressedFrameLength = 0;
		for (CompressionType type : CompressionType.values()) {
			if (CompressionFactory.isAvailable(type)) {
				CompressionCodec codec = CompressionFactory.createCodec(type);
				maxCompressedFrameLength = Math.max(maxCompressedFrameLength, codec.maxCompressedLength(MAX_CHUNK_SIZE));
			}
		}
		return maxCompressedFrameLength + ProtocolFrameCodec.HEADER_BYTES;
	}
}
