package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.message.configuration.ConfigurationChunkSizeMessage;
import pl.skidam.automodpack_core.protocol.netty.message.configuration.ConfigurationCompressionMessage;
import pl.skidam.automodpack_core.protocol.netty.message.configuration.UnknownConfigurationMessage;

public class ConfigurationHandler extends ByteToMessageDecoder {

	private final ConnectionLifetimeHandler lifetime;

	public ConfigurationHandler(ConnectionLifetimeHandler lifetime) {
		this.lifetime = lifetime;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		LOGGER.debug("Received a message (checking for configuration) with {} readable bytes", in.readableBytes());
		if (in.readableBytes() < 2) return;

		in.markReaderIndex();
		byte version = in.readByte();
		byte type = in.readByte();
		LOGGER.debug("Message version: {}, type: {}, readable bytes: {}", version, type, in.readableBytes());
		if ((type & 0xF0) != 0x40) {
			in.resetReaderIndex();
			out.add(in.readRetainedSlice(in.readableBytes()));
			ctx.pipeline().remove(this);
			return;
		}

		if (type == CONFIGURATION_ECHO_TYPE) {
			if (version != LATEST_SUPPORTED_PROTOCOL_VERSION) {
				rejectProtocolVersion(ctx, version);
				return;
			}
			ctx.channel().attr(NettyServer.PROTOCOL_VERSION).set(version);
			LOGGER.debug("Negotiated {} protocol version with the client", version);
			ctx.pipeline().remove(this);
			lifetime.configurationComplete(ctx);
			LOGGER.debug("Removed ConfigurationHandler from pipeline after receiving echo configuration message.");
		} else if (type == CONFIGURATION_KEEPALIVE_TYPE) {
			// A client parked on its certificate-trust decision heartbeats these to keep the transport warm; absorbing
			// them must never complete the configuration handshake nor elicit a reply.
			LOGGER.debug("Absorbed a pre-configuration keepalive from the client");
		} else if (type == CONFIGURATION_COMPRESSION_TYPE) {
			if (in.readableBytes() < 1) {
				in.resetReaderIndex();
				return;
			}

			CompressionType requested;
			try {
				requested = CompressionType.fromWireId(in.readByte());
			} catch (IllegalArgumentException e) {
				LOGGER.debug("Received unsupported compression type", e);
				ctx.close();
				return;
			}

			CompressionType selected = CompressionFactory.isAvailable(requested) ? requested : CompressionType.GZIP;
			NettyServer.setCompression(ctx.channel(), selected);
			ctx.writeAndFlush(new ConfigurationCompressionMessage(LATEST_SUPPORTED_PROTOCOL_VERSION, selected).toByteBuf());
			LOGGER.debug("Negotiated configuration: compression {}", selected);
		} else if (type == CONFIGURATION_CHUNK_SIZE_TYPE) {
			if (in.readableBytes() < Integer.BYTES) {
				in.resetReaderIndex();
				return;
			}

			int clientChunkSize = in.readInt();
			int negotiatedChunkSize = clientChunkSize >= MIN_CHUNK_SIZE && clientChunkSize <= MAX_CHUNK_SIZE ? clientChunkSize : DEFAULT_CHUNK_SIZE;
			ctx.channel().attr(NettyServer.CHUNK_SIZE).set(negotiatedChunkSize);
			ctx.writeAndFlush(new ConfigurationChunkSizeMessage(LATEST_SUPPORTED_PROTOCOL_VERSION, negotiatedChunkSize).toByteBuf());
			LOGGER.debug("Negotiated configuration: chunk size {}", negotiatedChunkSize);
		} else {
			LOGGER.debug("Received unknown configuration message type: {} version: {}", type, version);
			ctx.writeAndFlush(new UnknownConfigurationMessage(LATEST_SUPPORTED_PROTOCOL_VERSION).toByteBuf());
		}
	}

	/**
	 * Both ends ship together, so negotiation accepts exactly one version: anything else gets an ERROR frame and a closed socket. The reply echoes the client's claimed version and stays raw - the compression handler is
	 * not involved yet.
	 */
	private static void rejectProtocolVersion(ChannelHandlerContext ctx, byte version) {
		LOGGER.debug("Rejected protocol version {} from client", version);
		byte[] message = "Unsupported protocol version".getBytes(StandardCharsets.UTF_8);
		ByteBuf error = ctx.alloc().buffer(2 + Integer.BYTES + message.length);
		error.writeByte(version);
		error.writeByte(ERROR);
		error.writeInt(message.length);
		error.writeBytes(message);
		ctx.writeAndFlush(error).addListener(ChannelFutureListener.CLOSE);
	}
}
