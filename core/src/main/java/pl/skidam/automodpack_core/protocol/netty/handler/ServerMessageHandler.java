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
import pl.skidam.automodpack_core.protocol.DownloadBatchProtocol;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.BatchFileRequestMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.EchoMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.FileRequestMessage;

public class ServerMessageHandler extends SimpleChannelInboundHandler<ProtocolMessage> {

	private final NettyServer server;
	private String authenticatedSecret;
	private byte protocolVersion;
	private int chunkSize;
	private final Deque<ResolvedBatchItem> batchQueue = new ArrayDeque<>();
	private boolean batchTransferActive;
	private boolean legacyTransferActive;
	private HeapChunkedNioStream activeTransferStream;

	public ServerMessageHandler(NettyServer server) {
		this.server = server;
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		closeActiveTransferStream();
		batchQueue.clear();
		batchTransferActive = false;
		legacyTransferActive = false;
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
				if (legacyTransferActive || batchTransferActive || !batchQueue.isEmpty()) {
					sendError(ctx, protocolVersion, "A file transfer is already in progress");
					break;
				}
				FileRequestMessage fileRequest = (FileRequestMessage) msg;
				sendFile(ctx, fileRequest.getFileHash());
				break;
			case BATCH_FILE_REQUEST_TYPE :
				if (protocolVersion != DownloadBatchProtocol.VERSION) {
					sendError(ctx, protocolVersion, "Queued downloads require protocol version 2");
					break;
				}
				enqueueBatch(ctx, (BatchFileRequestMessage) msg);
				break;
			default :
				sendError(ctx, protocolVersion, "Unknown message type");
		}
	}

	private void enqueueBatch(ChannelHandlerContext ctx, BatchFileRequestMessage request) {
		if (legacyTransferActive || batchTransferActive || !batchQueue.isEmpty()) {
			sendError(ctx, protocolVersion, "A queued download is already in progress");
			return;
		}

		for (BatchFileRequestMessage.Item item : request.getItems()) {
			Optional<Path> optionalPath = resolvePath(item.key());
			if (optionalPath.isEmpty()) {
				batchQueue.add(new ResolvedBatchItem(item.itemId(), null, 0, "File not found"));
				continue;
			}

			Path path = optionalPath.get();
			try {
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Path is no longer a regular file");
				long fileSize = Files.size(path);
				if (fileSize < 0) throw new IOException("Negative file size");
				batchQueue.add(new ResolvedBatchItem(item.itemId(), path, fileSize, null));
			} catch (IOException e) {
				batchQueue.add(new ResolvedBatchItem(item.itemId(), null, 0, "File metadata could not be read"));
			}
		}

		pumpBatch(ctx);
	}

	private void pumpBatch(ChannelHandlerContext ctx) {
		if (batchTransferActive || batchQueue.isEmpty() || !ctx.channel().isActive()) return;
		batchTransferActive = true;
		ResolvedBatchItem item = batchQueue.remove();
		if (item.failureMessage() != null) {
			advanceBatch(ctx, sendItemFailure(ctx, item.itemId(), item.failureMessage()));
			return;
		}

		FileChannel channel = null;
		boolean headerQueued = false;
		try {
			if (item.fileSize() > 0) channel = FileChannel.open(item.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
			ByteBuf responseHeader = ctx.alloc().buffer(2 + Integer.BYTES + Byte.BYTES + Long.BYTES);
			responseHeader.writeByte(protocolVersion);
			responseHeader.writeByte(BATCH_ITEM_RESPONSE_TYPE);
			responseHeader.writeInt(item.itemId());
			responseHeader.writeByte(DownloadBatchProtocol.ITEM_SUCCESS);
			responseHeader.writeLong(item.fileSize());
			ChannelFuture headerFuture = writeControlAndFlush(ctx, responseHeader);
			headerQueued = true;
			if (item.fileSize() == 0) {
				if (channel != null) channel.close();
				onCompletion(ctx, headerFuture, completed -> {
					if (!completed.isSuccess()) {
						closeBatchConnection(ctx);
						return;
					}
					advanceBatch(ctx, sendItemEot(ctx, item.itemId()));
				});
				return;
			}

			HeapChunkedNioStream stream = new HeapChunkedNioStream(channel, chunkSize, item.fileSize());
			activeTransferStream = stream;
			ChannelFuture streamFuture = ctx.writeAndFlush(stream);
			onCompletion(ctx, streamFuture, future -> {
				if (!future.isSuccess() || stream.progress() != item.fileSize()) {
					closeBatchConnection(ctx);
					return;
				}
				if (activeTransferStream == stream) activeTransferStream = null;
				advanceBatch(ctx, sendItemEot(ctx, item.itemId()));
			});
		} catch (Exception e) {
			if (channel != null) {
				try {
					channel.close();
				} catch (IOException ignored) {
					// The channel is already unusable.
				}
			}
			if (headerQueued) {
				closeBatchConnection(ctx);
			} else {
				advanceBatch(ctx, sendItemFailure(ctx, item.itemId(), "File transfer could not be opened"));
			}
		}
	}

	private void advanceBatch(ChannelHandlerContext ctx, ChannelFuture future) {
		onCompletion(ctx, future, completed -> {
			if (!completed.isSuccess()) {
				closeBatchConnection(ctx);
				return;
			}
			batchTransferActive = false;
			if (ctx.channel().isActive()) ctx.executor().execute(() -> pumpBatch(ctx));
		});
	}

	private ChannelFuture sendItemFailure(ChannelHandlerContext ctx, int itemId, String message) {
		byte[] errorBytes = message.getBytes(StandardCharsets.UTF_8);
		if (errorBytes.length > DownloadBatchProtocol.MAX_ERROR_BYTES) errorBytes = Arrays.copyOf(errorBytes, DownloadBatchProtocol.MAX_ERROR_BYTES);
		ByteBuf error = ctx.alloc().buffer(2 + Integer.BYTES + Byte.BYTES + Integer.BYTES + errorBytes.length);
		error.writeByte(protocolVersion);
		error.writeByte(BATCH_ITEM_RESPONSE_TYPE);
		error.writeInt(itemId);
		error.writeByte(DownloadBatchProtocol.ITEM_FAILURE);
		error.writeInt(errorBytes.length);
		error.writeBytes(errorBytes);
		return writeControlAndFlush(ctx, error);
	}

	private ChannelFuture sendItemEot(ChannelHandlerContext ctx, int itemId) {
		ByteBuf eot = ctx.alloc().buffer(2 + Integer.BYTES);
		eot.writeByte(protocolVersion);
		eot.writeByte(END_OF_TRANSMISSION);
		eot.writeInt(itemId);
		return writeControlAndFlush(ctx, eot);
	}

	private void closeBatchConnection(ChannelHandlerContext ctx) {
		batchQueue.clear();
		batchTransferActive = false;
		closeActiveTransferStream();
		ctx.close();
	}

	private void closeActiveTransferStream() {
		HeapChunkedNioStream stream = activeTransferStream;
		activeTransferStream = null;
		if (stream == null) return;
		try {
			stream.close();
		} catch (Exception ignored) {
			// The channel is already being closed.
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
		legacyTransferActive = true;

		ByteBuf responseHeader = ctx.alloc().buffer(1 + 1 + 8);
		responseHeader.writeByte(this.protocolVersion);
		responseHeader.writeByte(FILE_RESPONSE_TYPE);
		responseHeader.writeLong(fileSize);
		onCompletion(ctx, writeControlAndFlush(ctx, responseHeader), future -> {
			if (!future.isSuccess()) {
				legacyTransferActive = false;
				ctx.close();
			}
		});

		if (fileSize == 0) {
			sendEOT(ctx);
			return;
		}

		ReadableByteChannel channel = null;

		try {
			channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
			HeapChunkedNioStream chunkedStream = new HeapChunkedNioStream(channel, this.chunkSize);
			activeTransferStream = chunkedStream;

			onCompletion(ctx, ctx.writeAndFlush(chunkedStream), future -> {
				if (future.isSuccess()) {
					if (activeTransferStream == chunkedStream) activeTransferStream = null;
					sendEOT(ctx);
				} else {
					closeTransferConnection(ctx);
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
			legacyTransferActive = false;
			ctx.close();
		}
	}

	private void closeTransferConnection(ChannelHandlerContext ctx) {
		legacyTransferActive = false;
		closeActiveTransferStream();
		ctx.close();
	}

	public Optional<Path> resolvePath(final String sha1) {
		return server.getPath(sha1);
	}

	private void sendError(ChannelHandlerContext ctx, byte version, String errorMessage) {
		legacyTransferActive = false;
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
		onCompletion(ctx, writeControlAndFlush(ctx, eot), future -> {
			legacyTransferActive = false;
			if (!future.isSuccess()) ctx.close();
		});
	}

	private static void onCompletion(ChannelHandlerContext ctx, ChannelFuture future, ChannelFutureListener listener) {
		future.addListener(completed -> ctx.executor().execute(() -> {
			try {
				listener.operationComplete((ChannelFuture) completed);
			} catch (Exception e) {
				ctx.fireExceptionCaught(e);
			}
		}));
	}

	private static ChannelFuture writeControlAndFlush(ChannelHandlerContext ctx, Object message) {
		ChannelHandlerContext chunkedContext = ctx.pipeline().context(ChunkedWriteHandler.class);
		if (chunkedContext == null) return ctx.writeAndFlush(message);
		return chunkedContext.writeAndFlush(message);
	}

	private record ResolvedBatchItem(int itemId, Path path, long fileSize, String failureMessage) {}
}
