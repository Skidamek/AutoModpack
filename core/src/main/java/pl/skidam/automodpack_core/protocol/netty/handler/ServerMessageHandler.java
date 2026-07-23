package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletionException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.stream.ChunkedNioStream;
import io.netty.util.CharsetUtil;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.EchoMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.FileRequestMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.RefreshRequestMessage;
import pl.skidam.automodpack_core.utils.LockFreeInputStream;

public class ServerMessageHandler extends SimpleChannelInboundHandler<ProtocolMessage> {

	private final Map<byte[], String> secretLookup = new HashMap<>();
	private byte protocolVersion;
	private int chunkSize;

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		hostServer.removeConnection(ctx.channel());
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
				ctx.writeAndFlush(echoBuf);
				ctx.channel().close();
				break;
			case FILE_REQUEST_TYPE :
				FileRequestMessage fileRequest = (FileRequestMessage) msg;
				sendFile(ctx, fileRequest.getFileHash());
				break;
			case REFRESH_REQUEST_TYPE :
				RefreshRequestMessage refreshRequest = (RefreshRequestMessage) msg;
				refreshModpackFiles(ctx, refreshRequest.getFileHashesList());
				break;
			default :
				sendError(ctx, protocolVersion, "Unknown message type");
		}
	}

	private void refreshModpackFiles(ChannelHandlerContext context, byte[][] fileHashesList) throws IOException {
		Set<String> hashes = new TreeSet<>();
		for (byte[] hash : fileHashesList) hashes.add(new String(hash, StandardCharsets.UTF_8));
		LOGGER.info("Received full modpack regeneration request after failed hashes: {}", hashes);
		try {
			var manifest = modpackExecutor.regenerateFullManifest().join();
			LOGGER.info("Sending regenerated full manifest {} with {} files", manifest.modpackId, manifest.list.size());
			sendFile(context, new byte[0]);
		} catch (CompletionException e) {
			LOGGER.error("Failed to regenerate full modpack manifest", e);
			sendError(context, protocolVersion, "Modpack regeneration failed");
		}
	}

	private boolean validateSecret(ChannelHandlerContext ctx, SocketAddress address, byte[] secret) {
		String decodedSecret = secretLookup.get(secret);
		boolean addConnection = false;
		if (decodedSecret == null) {
			decodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
			addConnection = true;
			secretLookup.put(secret, decodedSecret);
		}

		boolean valid = Secrets.isSecretValid(decodedSecret, address);

		if (addConnection && valid) hostServer.addConnection(ctx.channel(), decodedSecret);

		return valid;
	}

	private void sendFile(ChannelHandlerContext ctx, byte[] bsha1) throws IOException {
		final String sha1 = new String(bsha1, CharsetUtil.UTF_8);
		final Optional<Path> optionalPath = resolvePath(sha1);

		if (optionalPath.isEmpty() || !Files.exists(optionalPath.get())) {
			sendError(ctx, this.protocolVersion, "File not found");
			return;
		}

		final Path path = optionalPath.get();
		final long fileSize = Files.size(path);

		ByteBuf responseHeader = ctx.alloc().buffer(1 + 1 + 8);
		responseHeader.writeByte(this.protocolVersion);
		responseHeader.writeByte(FILE_RESPONSE_TYPE);
		responseHeader.writeLong(fileSize);
		ctx.writeAndFlush(responseHeader);

		if (fileSize == 0) {
			sendEOT(ctx);
			return;
		}

		ReadableByteChannel channel = null;

		try {
			channel = LockFreeInputStream.openChannel(path);
			ChunkedNioStream chunkedStream = new ChunkedNioStream(channel, this.chunkSize);

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
		if (sha1.isBlank()) return Optional.of(hostModpackContentFile);
		return hostServer.getPath(sha1);
	}

	private void sendError(ChannelHandlerContext ctx, byte version, String errorMessage) {
		byte[] errMsgBytes = errorMessage.getBytes(CharsetUtil.UTF_8);
		ByteBuf errorBuf = ctx.alloc().buffer(1 + 1 + 4 + errMsgBytes.length);
		errorBuf.writeByte(version);
		errorBuf.writeByte(ERROR);
		errorBuf.writeInt(errMsgBytes.length);
		errorBuf.writeBytes(errMsgBytes);
		ctx.writeAndFlush(errorBuf);
		ctx.channel().close();
	}

	private void sendEOT(ChannelHandlerContext ctx) {
		ByteBuf eot = ctx.alloc().buffer(2);
		eot.writeByte(this.protocolVersion);
		eot.writeByte(END_OF_TRANSMISSION);
		ctx.writeAndFlush(eot);
	}
}
