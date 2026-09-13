package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.ProtocolFrameCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionFactory;
import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.EchoMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.FileRequestMessage;

/**
 * Serves post-handshake protocol messages. File sends produce frames off the event loop: each transfer submits one worker to the server's sender executor holding a FileChannel plus
 * one reusable chunk buffer, frames and compresses every chunk with its own grow-only {@link ProtocolFrameCodec.FrameScratch}, and writes the finished frame at the compression
 * encoder's own pipeline context so the pre-encoded frame bypasses the encoder but still flows through everything head-ward (TLS, shaping). Per channel, ordering is by submission:
 * the worker awaits the response header write before its first frame and blocks on every frame write, so at most one frame is in flight per transfer and the socket drain rate is the
 * backpressure. Two tripwires bound the damage a broken peer can do: concurrent transfers per connection are capped, and every awaited write fails once the socket stops draining
 * for the {@code TRANSFER_WRITE_STALL_TIMEOUT} window, so a stalled client can neither pin the worker nor its buffers forever.
 */
public class ServerMessageHandler extends SimpleChannelInboundHandler<ProtocolMessage> {

	private final NettyServer server;
	private final AtomicInteger inFlightTransfers = new AtomicInteger();
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

		// Validate the secret; rejection reasons are logged by the auth layer
		if (!validateSecret(ctx, address, msg.getSecret())) {
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
		if (!authenticatedSecret.equals(decodedSecret)) {
			LOGGER.warn("Connection from {} tried to switch to a different secret", address);
			return false;
		}
		return true;
	}

	private void sendFile(ChannelHandlerContext ctx, byte[] bsha1) throws IOException {
		if (inFlightTransfers.get() >= MAX_CONCURRENT_TRANSFERS_PER_CONNECTION) {
			sendError(ctx, this.protocolVersion, "Too many concurrent transfers");
			return;
		}

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
		ChannelFuture headerFuture = writeControlAndFlush(ctx, responseHeader);

		if (fileSize == 0) {
			sendEOT(ctx);
			return;
		}

		final byte protocolVersion = this.protocolVersion;
		final int chunkSize = this.chunkSize;

		FileChannel file = null;
		try {
			// Resolved eagerly on the loop so the worker never reads channel state racing a later reconfiguration.
			final CompressionCodec codec = compressionCodec(ctx);
			final ChannelHandlerContext encoderContext = encoderContext(ctx);
			final FileChannel opened = FileChannel.open(path, StandardOpenOption.READ);
			file = opened;
			inFlightTransfers.incrementAndGet();
			server.senderExecutor().execute(() -> streamFile(ctx, opened, fileSize, chunkSize, protocolVersion, headerFuture, codec, encoderContext));
		} catch (Exception e) {
			inFlightTransfers.decrementAndGet();
			closeQuietly(file);
			sendError(ctx, this.protocolVersion, "File transfer error: " + e.getMessage());
		}
	}

	/** One transfer's worker, running entirely off the event loop; success sends EOT, any failure reproduces the write-failure error message. */
	private void streamFile(ChannelHandlerContext ctx, FileChannel file, long fileSize, int chunkSize, byte protocolVersion, ChannelFuture headerFuture, CompressionCodec codec, ChannelHandlerContext encoderContext) {
		ProtocolFrameCodec.FrameScratch scratch = new ProtocolFrameCodec.FrameScratch();
		ByteBuf chunk = ctx.alloc().heapBuffer(chunkSize, chunkSize);
		ByteBuffer chunkBuffer = chunk.nioBuffer(0, chunkSize);
		Throwable failure = null;
		try {
			Throwable headerFailure = awaitFrameFlush(ctx.channel(), headerFuture);
			if (headerFailure == null && !headerFuture.isSuccess()) headerFailure = causeOf(headerFuture);
			if (headerFailure != null) {
				failure = headerFailure;
			} else {
				long sent = 0;
				while (failure == null && sent < fileSize) {
					chunkBuffer.clear();
					int length = fill(file, chunkBuffer);
					if (length == 0) break;
					chunk.writerIndex(length);
					sent += length;
					failure = writeFrame(ctx, chunk, length, codec, chunkSize, scratch, encoderContext);
				}
			}
		} catch (Exception e) {
			failure = e;
		} finally {
			inFlightTransfers.decrementAndGet();
			chunk.release();
			closeQuietly(file);
		}
		if (failure == null) {
			executeOnLoop(ctx.channel(), () -> sendEOT(ctx));
		} else {
			final Throwable outcome = failure;
			executeOnLoop(ctx.channel(), () -> sendError(ctx, protocolVersion, "File transfer error: " + outcome.getMessage()));
		}
	}

	/** Compresses one chunk into a finished frame, writes it at the encoder's context and blocks until it is handed to the socket; returns null on success or the failure. */
	private static Throwable writeFrame(ChannelHandlerContext ctx, ByteBuf chunk, int length, CompressionCodec codec, int chunkSize, ProtocolFrameCodec.FrameScratch scratch, ChannelHandlerContext encoderContext) {
		ByteBuf frame = ctx.alloc().buffer(ProtocolFrameCodec.HEADER_BYTES + codec.maxCompressedLength(length));
		try {
			ProtocolFrameCodec.write(frame, codec, chunk, chunkSize, scratch);
			// Netty owns the frame once writeAndFlush accepted it, even when the await below is interrupted or fails.
			ChannelFuture written = encoderContext.writeAndFlush(frame);
			Throwable stall = awaitFrameFlush(ctx.channel(), written);
			if (stall != null) return stall;
			return written.isSuccess() ? null : causeOf(written);
		} catch (Exception e) {
			frame.release();
			return e;
		}
	}

	/**
	 * Awaits one flush reaching the socket, failing the transfer once the peer stops draining entirely for
	 * {@link NetUtils#TRANSFER_WRITE_STALL_TIMEOUT}. Only zero progress trips it: the window resets whenever
	 * the pending byte count moves, so a slow but flowing link never times out, and the write is bounded so
	 * the worker, its chunk buffer and its scratch can never be pinned by a silent peer.
	 */
	private static Throwable awaitFrameFlush(Channel channel, ChannelFuture written) {
		long stallWindowNanos = TRANSFER_WRITE_STALL_TIMEOUT.toNanos();
		long progressDeadline = System.nanoTime() + stallWindowNanos;
		long lastPending = -1;
		try {
			while (!written.await(1, TimeUnit.SECONDS)) {
				long pending = pendingOutboundBytes(channel);
				if (pending != lastPending) {
					lastPending = pending;
					progressDeadline = System.nanoTime() + stallWindowNanos;
				} else if (System.nanoTime() - progressDeadline >= 0) {
					channel.close();
					return new IOException("Write stalled: the peer stopped draining the connection");
				}
			}
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return e;
		}
	}

	/** The drain gauge for the stall window: pending bytes above the writability floor, zero while the outbound buffer sits under it. */
	private static long pendingOutboundBytes(Channel channel) {
		return channel.isWritable() ? 0 : channel.bytesBeforeWritable();
	}

	private static Throwable causeOf(ChannelFuture future) {
		Throwable cause = future.cause();
		return cause != null ? cause : new IOException("Unknown");
	}

	private static int fill(FileChannel file, ByteBuffer buffer) throws IOException {
		int length = 0;
		while (buffer.hasRemaining()) {
			int read = file.read(buffer);
			if (read < 0) break;
			length += read;
		}
		return length;
	}

	private static void closeQuietly(FileChannel file) {
		if (file == null) return;
		try {
			file.close();
		} catch (IOException ignored) {
			// Ignored
		}
	}

	/** Runs the finishing write on the event loop, or drops it when the loop is already shutting down with the server. */
	private static void executeOnLoop(Channel channel, Runnable action) {
		try {
			channel.eventLoop().execute(action);
		} catch (RejectedExecutionException rejected) {
			// The server is stopping; there is no live channel left to receive the message.
		}
	}

	private CompressionCodec compressionCodec(ChannelHandlerContext ctx) {
		CompressionType selected = ctx.channel().attr(NettyServer.COMPRESSION_TYPE).get();
		if (selected == null) throw new IllegalStateException("Compression type has not been configured");
		return CompressionFactory.createCodec(selected);
	}

	private static ChannelHandlerContext encoderContext(ChannelHandlerContext ctx) {
		ChannelHandlerContext encoderContext = ctx.pipeline().context(CompressionEncoder.class);
		if (encoderContext == null) throw new IllegalStateException("Compression encoder is not in the pipeline");
		return encoderContext;
	}

	private Optional<Path> resolvePath(final String sha1) {
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
