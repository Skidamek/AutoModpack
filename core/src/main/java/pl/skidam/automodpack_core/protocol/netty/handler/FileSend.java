package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.stream.ChunkedWriteHandler;

import pl.skidam.automodpack_core.protocol.ProtocolFrameCodec;
import pl.skidam.automodpack_core.protocol.compression.CompressionCodec;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

/**
 * One connection's file-send runtime: in-flight cap, stall window, worker submit, and EOT/error completion.
 * The inbound handler stays a switch over protocol messages.
 */
final class FileSend {
	private final NettyServer server;
	private final AtomicInteger inFlightTransfers = new AtomicInteger();

	FileSend(NettyServer server) {
		this.server = server;
	}

	void send(ChannelHandlerContext ctx, byte[] bsha1, byte protocolVersion, int chunkSize, long offset, Long endInclusive) throws IOException {
		final String sha1 = new String(bsha1, StandardCharsets.UTF_8);
		final Optional<Path> optionalPath = server.getPath(sha1);

		if (optionalPath.isEmpty() || Files.isSymbolicLink(optionalPath.get()) || !Files.isRegularFile(optionalPath.get(), LinkOption.NOFOLLOW_LINKS)) {
			LOGGER.warn("Hosted object not found: {}", sha1);
			sendError(ctx, protocolVersion, "File not found");
			return;
		}

		final Path path = optionalPath.get();
		final long fileSize = Files.size(path);
		final long length = (endInclusive == null ? fileSize : Math.min(endInclusive + 1, fileSize)) - offset;

		if (offset < 0 || offset > fileSize || length < 0) {
			sendError(ctx, protocolVersion, "Invalid range");
			return;
		}

		if (length == 0) {
			writeControlAndFlush(ctx, fileResponseHeader(ctx, protocolVersion, 0));
			sendEOT(ctx, protocolVersion);
			return;
		}

		if (!tryAcquire()) {
			sendError(ctx, protocolVersion, "Too many concurrent transfers");
			return;
		}

		FileChannel file = null;
		try {
			ChannelFuture headerFuture = writeControlAndFlush(ctx, fileResponseHeader(ctx, protocolVersion, length));
			final CompressionCodec codec = NettyServer.compressionCodec(ctx.channel());
			final ChannelHandlerContext encoderContext = encoderContext(ctx);
			final FileChannel opened = FileChannel.open(path, StandardOpenOption.READ);
			file = opened;
			opened.position(offset);
			server.senderExecutor().execute(() -> streamFile(ctx, opened, length, chunkSize, protocolVersion, headerFuture, codec, encoderContext));
		} catch (Exception e) {
			inFlightTransfers.decrementAndGet();
			closeQuietly(file);
			// The response header is already in flight, so an ERROR frame here would land inside the data stream the client is
			// already reading; the only honest completion is dropping the connection.
			LOGGER.error("File transfer of {} aborted after the response header was sent", sha1, e);
			ctx.channel().close();
		}
	}

	/** Occupies one in-flight slot or fails; two requests cannot both pass a stale get()-then-increment. */
	private boolean tryAcquire() {
		while (true) {
			int inFlight = inFlightTransfers.get();
			if (inFlight >= MAX_CONCURRENT_TRANSFERS_PER_CONNECTION) return false;
			if (inFlightTransfers.compareAndSet(inFlight, inFlight + 1)) return true;
		}
	}

	private void streamFile(ChannelHandlerContext ctx, FileChannel file, long length, int chunkSize, byte protocolVersion, ChannelFuture headerFuture, CompressionCodec codec, ChannelHandlerContext encoderContext) {
		ByteBuf chunk = null;
		Throwable failure = null;
		try {
			ProtocolFrameCodec.FrameScratch scratch = new ProtocolFrameCodec.FrameScratch();
			chunk = ctx.alloc().heapBuffer(chunkSize, chunkSize);
			ByteBuffer chunkBuffer = chunk.nioBuffer(0, chunkSize);
			Throwable headerFailure = awaitFrameFlush(ctx.channel(), headerFuture);
			if (headerFailure == null && !headerFuture.isSuccess()) headerFailure = causeOf(headerFuture);
			if (headerFailure != null) {
				failure = headerFailure;
			} else {
				long sent = 0;
				while (failure == null && sent < length) {
					chunkBuffer.clear();
					chunkBuffer.limit((int) Math.min(chunkSize, length - sent));
					int read = fill(file, chunkBuffer);
					if (read == 0) break;
					chunk.writerIndex(read);
					sent += read;
					failure = writeFrame(ctx, chunk, read, codec, chunkSize, scratch, encoderContext);
				}
				if (failure == null && sent < length) failure = new IOException("File ended before the requested range was streamed");
			}
		} catch (Exception e) {
			failure = e;
		} finally {
			inFlightTransfers.decrementAndGet();
			if (chunk != null) chunk.release();
			closeQuietly(file);
		}
		if (failure == null) {
			executeOnLoop(ctx.channel(), () -> sendEOT(ctx, protocolVersion));
		} else {
			// The client is mid-stream: an ERROR frame would be consumed as file bytes, so the transfer's only honest
			// completion is dropping the connection; the reason lives in this log.
			LOGGER.error("File transfer failed {} of {} bytes: {}", file, length, failure.getMessage(), failure);
			executeOnLoop(ctx.channel(), ctx.channel()::close);
		}
	}

	private static Throwable writeFrame(ChannelHandlerContext ctx, ByteBuf chunk, int length, CompressionCodec codec, int chunkSize, ProtocolFrameCodec.FrameScratch scratch, ChannelHandlerContext encoderContext) {
		ByteBuf frame = ctx.alloc().buffer(ProtocolFrameCodec.HEADER_BYTES + codec.maxCompressedLength(length));
		try {
			ProtocolFrameCodec.write(frame, codec, chunk, chunkSize, scratch);
			ChannelFuture written = encoderContext.writeAndFlush(frame);
			Throwable stall = awaitFrameFlush(ctx.channel(), written);
			if (stall != null) return stall;
			return written.isSuccess() ? null : causeOf(written);
		} catch (Exception e) {
			frame.release();
			return e;
		}
	}

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
		}
	}

	private static void executeOnLoop(Channel channel, Runnable action) {
		try {
			channel.eventLoop().execute(action);
		} catch (RejectedExecutionException rejected) {
		}
	}

	private static ChannelHandlerContext encoderContext(ChannelHandlerContext ctx) {
		ChannelHandlerContext encoderContext = ctx.pipeline().context(CompressionEncoder.class);
		if (encoderContext == null) throw new IllegalStateException("Compression encoder is not in the pipeline");
		return encoderContext;
	}

	static void sendError(ChannelHandlerContext ctx, byte version, String errorMessage) {
		byte[] errMsgBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
		ByteBuf errorBuf = ctx.alloc().buffer(1 + 1 + 4 + errMsgBytes.length);
		errorBuf.writeByte(version);
		errorBuf.writeByte(ERROR);
		errorBuf.writeInt(errMsgBytes.length);
		errorBuf.writeBytes(errMsgBytes);
		writeControlAndFlush(ctx, errorBuf).addListener(ChannelFutureListener.CLOSE);
	}

	private static ByteBuf fileResponseHeader(ChannelHandlerContext ctx, byte protocolVersion, long fileSize) {
		ByteBuf responseHeader = ctx.alloc().buffer(1 + 1 + 8);
		responseHeader.writeByte(protocolVersion);
		responseHeader.writeByte(FILE_RESPONSE_TYPE);
		responseHeader.writeLong(fileSize);
		return responseHeader;
	}

	private static void sendEOT(ChannelHandlerContext ctx, byte protocolVersion) {
		ByteBuf eot = ctx.alloc().buffer(2);
		eot.writeByte(protocolVersion);
		eot.writeByte(END_OF_TRANSMISSION);
		writeControlAndFlush(ctx, eot);
	}

	/** Completes a conditional document request whose expected hash still matches; nothing but this frame follows. */
	static void sendUnchanged(ChannelHandlerContext ctx, byte protocolVersion) {
		ByteBuf unchanged = ctx.alloc().buffer(2);
		unchanged.writeByte(protocolVersion);
		unchanged.writeByte(UNCHANGED_TYPE);
		writeControlAndFlush(ctx, unchanged);
	}

	static ChannelFuture writeControlAndFlush(ChannelHandlerContext ctx, Object message) {
		ChannelHandlerContext chunkedContext = ctx.pipeline().context(ChunkedWriteHandler.class);
		if (chunkedContext == null) return ctx.writeAndFlush(message);
		return chunkedContext.writeAndFlush(message);
	}
}
