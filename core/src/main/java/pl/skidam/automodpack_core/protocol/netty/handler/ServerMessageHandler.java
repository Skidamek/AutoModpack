package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.EchoMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.FileRequestMessage;

public class ServerMessageHandler extends SimpleChannelInboundHandler<ProtocolMessage> {

	private final NettyServer server;
	private String authenticatedSecret;
	private byte protocolVersion;
	private int chunkSize;

	public ServerMessageHandler(NettyServer server) {
		this.server = server;
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		server.removeConnection(ctx.channel());
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, ProtocolMessage msg) throws Exception {
		this.protocolVersion = ctx.pipeline().channel().attr(NettyServer.PROTOCOL_VERSION).get();
		this.chunkSize = ctx.pipeline().channel().attr(NettyServer.CHUNK_SIZE).get();

		byte clientProtocolVersion = msg.getVersion();

		if (protocolVersion != clientProtocolVersion) {
			sendError(ctx, protocolVersion, "Protocol version mismatch");
			return;
		}

		SocketAddress address = ctx.channel().attr(NettyServer.REAL_REMOTE_ADDR).get();

		// Validate the secret
		if (!validateSecret(ctx, address, msg.getSecret())) {
			LOGGER.warn("Player with address {} tried to connect but we've received an invalid secret - make sure they are whitelisted", address);
			sendError(ctx, protocolVersion, "Authentication failed");
			return;
		}

		switch (msg.getType()) {
			case ECHO_TYPE :
				EchoMessage echoMsg = (EchoMessage) msg;
				ByteBuf echoBuf = ctx.alloc().buffer(1 + 1 + msg.getSecret().length + echoMsg.getData().length);
				echoBuf.writeByte(protocolVersion);
				echoBuf.writeByte(ECHO_TYPE);
				echoBuf.writeBytes(echoMsg.getSecret());
				echoBuf.writeBytes(echoMsg.getData());
				writeControlAndFlush(ctx, echoBuf).addListener(ChannelFutureListener.CLOSE);
				break;
			case FILE_REQUEST_TYPE :
				FileRequestMessage fileRequest = (FileRequestMessage) msg;
				sendFile(ctx, fileRequest.getFileHash());
				break;
			default :
				sendError(ctx, protocolVersion, "Unknown message type");
		}
	}

	private boolean validateSecret(ChannelHandlerContext ctx, SocketAddress address, byte[] secret) {
		String decodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
		if (!Secrets.isSecretValid(decodedSecret, address)) return false;
		if (authenticatedSecret == null) {
			authenticatedSecret = decodedSecret;
			server.addConnection(ctx.channel(), decodedSecret);
		}
		return authenticatedSecret.equals(decodedSecret);
	}

	private void sendFile(ChannelHandlerContext ctx, byte[] bsha1) throws IOException {
		final String sha1 = new String(bsha1, StandardCharsets.UTF_8);
		final Optional<Path> optionalPath = resolvePath(sha1);

		if (optionalPath.isEmpty() || Files.isSymbolicLink(optionalPath.get()) || !Files.isRegularFile(optionalPath.get(), LinkOption.NOFOLLOW_LINKS)) {
			sendError(ctx, this.protocolVersion, "File not found");
			return;
		}

		final Path path = optionalPath.get();
		final long fileSize = Files.size(path);

		ByteBuf responseHeader = ctx.alloc().buffer(1 + 1 + 8);
		responseHeader.writeByte(this.protocolVersion);
		responseHeader.writeByte(FILE_RESPONSE_TYPE);
		responseHeader.writeLong(fileSize);
		writeControlAndFlush(ctx, responseHeader);

		if (fileSize == 0) {
			sendEOT(ctx);
			return;
		}

		ReadableByteChannel channel = null;

		try {
			channel = FileChannel.open(path, StandardOpenOption.READ);
			HeapChunkedNioStream chunkedStream = new HeapChunkedNioStream(channel, this.chunkSize);

			ctx.writeAndFlush(chunkedStream).addListener((ChannelFutureListener) future -> {
				if (future.isSuccess()) {
					sendEOT(ctx);
				} else {
					Throwable cause = future.cause();
					sendError(ctx, this.protocolVersion, "File transfer error: " + (cause != null ? cause.getMessage() : "Unknown"));
				}
			});

		} catch (Exception e) {
			if (channel != null) {
				try {
					channel.close();
				} catch (IOException ignored) {
					// Ignored
				}
			}
			sendError(ctx, this.protocolVersion, "File transfer error: " + e.getMessage());
		}
	}

	public Optional<Path> resolvePath(final String sha1) {
		return server.getPath(sha1);
	}

	private void sendError(ChannelHandlerContext ctx, byte version, String errorMessage) {
		byte[] errMsgBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
		ByteBuf errorBuf = ctx.alloc().buffer(1 + 1 + 4 + errMsgBytes.length);
		errorBuf.writeByte(version);
		errorBuf.writeByte(ERROR);
		errorBuf.writeInt(errMsgBytes.length);
		errorBuf.writeBytes(errMsgBytes);
		writeControlAndFlush(ctx, errorBuf).addListener(ChannelFutureListener.CLOSE);
	}

	private void sendEOT(ChannelHandlerContext ctx) {
		ByteBuf eot = ctx.alloc().buffer(2);
		eot.writeByte(this.protocolVersion);
		eot.writeByte(END_OF_TRANSMISSION);
		writeControlAndFlush(ctx, eot);
	}

	private static ChannelFuture writeControlAndFlush(ChannelHandlerContext ctx, Object message) {
		ChannelHandlerContext chunkedContext = ctx.pipeline().context(ChunkedWriteHandler.class);
		if (chunkedContext == null) return ctx.writeAndFlush(message);
		return chunkedContext.writeAndFlush(message);
	}
}
