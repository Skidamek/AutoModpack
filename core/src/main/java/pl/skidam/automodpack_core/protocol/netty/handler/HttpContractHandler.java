package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.DEFAULT_CHUNK_SIZE;
import static pl.skidam.automodpack_core.protocol.NetUtils.TRANSFER_WRITE_STALL_TIMEOUT;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;

import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Serves the URL contract (GET /head, GET /journal, GET /objects/<sha1>) over an already-TLS-terminated pipeline with
 * hand-rolled HTTP/1.1: one in-flight response per connection, keep-alive by default, requests arriving while a body
 * streams are held and served after it. No pipelining support; a pipelining client just gets serialized responses, so
 * unlike the custom protocol there is no per-connection in-flight transfer cap - one slot per connection by construction.
 */
public class HttpContractHandler extends ChannelInboundHandlerAdapter {

	/** Tripwire past any real request header block; only broken things or unbounded pipeliners touch it. */
	private static final int MAX_HEADER_BLOCK_BYTES = 8 * 1024;

	private static final String STATUS_200 = "200 OK";
	private static final String STATUS_206 = "206 Partial Content";
	private static final String STATUS_304 = "304 Not Modified";
	private static final String STATUS_400 = "400 Bad Request";
	private static final String STATUS_404 = "404 Not Found";
	private static final String STATUS_405 = "405 Method Not Allowed";
	private static final String STATUS_416 = "416 Range Not Satisfiable";

	private static final String CONTENT_TYPE = "application/octet-stream";

	private final NettyServer server;
	private final Executor senders;
	private ByteBuf cumulation;
	private boolean streaming;

	public HttpContractHandler(NettyServer server, Executor senders) {
		this.server = server;
		this.senders = senders;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (!(msg instanceof ByteBuf input)) {
			ctx.fireChannelRead(msg);
			return;
		}

		try {
			if (streaming) {
				// A pipelining client's next request waits until the current body drains; the header cap keeps the hold bounded.
				accumulate(input);
				if (cumulation.readableBytes() > MAX_HEADER_BLOCK_BYTES) rejectUnparseable(ctx);
				return;
			}
			accumulate(input);
			serveLoop(ctx);
		} finally {
			input.release();
		}
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) {
		if (cumulation != null) cumulation.discardSomeReadBytes();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		releaseCumulation();
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		releaseCumulation();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		LOGGER.debug("HTTP contract connection error", cause);
		ctx.close();
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		// The pipeline's all-idle reap: a silent connection holds a public FD for nothing. A streaming response writes
		// continuously, so the event can only fire for a connection with no request in flight and no body draining.
		if (evt instanceof IdleStateEvent) {
			LOGGER.debug("HTTP contract connection went idle; closing it");
			ctx.close();
			return;
		}
		super.userEventTriggered(ctx, evt);
	}

	private void releaseCumulation() {
		if (cumulation != null) {
			cumulation.release();
			cumulation = null;
		}
	}

	private void accumulate(ByteBuf input) {
		if (cumulation == null) {
			cumulation = Unpooled.buffer(Math.min(input.readableBytes() + 64, MAX_HEADER_BLOCK_BYTES + 1));
		} else if (cumulation.writableBytes() < input.readableBytes()) {
			ByteBuf grown = Unpooled.buffer(Math.max(cumulation.readableBytes() * 2, cumulation.readableBytes() + input.readableBytes()));
			grown.writeBytes(cumulation);
			cumulation.release();
			cumulation = grown;
		}
		cumulation.writeBytes(input);
	}

	/** Parsed every buffered request it can; leaves when a body starts streaming, the connection closes, or bytes run out. */
	private void serveLoop(ChannelHandlerContext ctx) {
		while (ctx.channel().isActive() && !streaming && cumulation != null) {
			int headerEnd = headerEnd(cumulation);
			// The cap binds the block whether or not a terminator has arrived: junk-then-terminator streams must find no
			// richer welcome than an unterminated trickle.
			if (headerEnd < 0 || headerEnd > MAX_HEADER_BLOCK_BYTES) {
				if (headerEnd > MAX_HEADER_BLOCK_BYTES || cumulation.readableBytes() > MAX_HEADER_BLOCK_BYTES) rejectUnparseable(ctx);
				return;
			}
			if (!handleRequest(ctx, headerEnd)) return;
		}
	}

	private void rejectUnparseable(ChannelHandlerContext ctx) {
		LOGGER.debug("HTTP request header block exceeded {} bytes; closing the connection", MAX_HEADER_BLOCK_BYTES);
		ctx.close();
	}

	private boolean rejectGarbage(ChannelHandlerContext ctx) {
		LOGGER.debug("Unparseable HTTP request; closing the connection");
		ctx.close();
		return false;
	}

	/** Handles one fully-buffered request head; false means the connection closes or the response body streams. */
	private boolean handleRequest(ChannelHandlerContext ctx, int headerEnd) {
		String request = cumulation.readCharSequence(headerEnd, StandardCharsets.UTF_8).toString();
		cumulation.skipBytes(4);
		cumulation.discardReadBytes();

		String[] lines = request.split("\r\n", -1);
		String[] requestLine = lines[0].split(" ");
		if (requestLine.length != 3 || (!requestLine[2].equals("HTTP/1.1") && !requestLine[2].equals("HTTP/1.0"))) return rejectGarbage(ctx);

		String method = requestLine[0];
		String target = requestLine[1];
		boolean keepAlive = requestLine[2].equals("HTTP/1.1");
		String ifNoneMatch = null;
		String range = null;
		for (int i = 1; i < lines.length; i++) {
			int colon = lines[i].indexOf(':');
			if (colon <= 0) continue;
			String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = lines[i].substring(colon + 1).trim();
			if (name.equals("if-none-match")) ifNoneMatch = value;
			else if (name.equals("range")) range = value;
			else if (name.equals("connection")) {
				String connection = value.toLowerCase(Locale.ROOT);
				if (connection.contains("close")) keepAlive = false;
				else if (connection.contains("keep-alive")) keepAlive = true;
			}
		}

		if (!method.equals("GET")) return respondOrClose(ctx, STATUS_405, 0, null, null, keepAlive);

		// The contract paths carry no encoding, so a percent-encoded target cannot name a route.
		if (target.indexOf('%') >= 0) {
			respondThenClose(ctx, STATUS_400, 0, null, null);
			return false;
		}

		String key = routeKey(target);
		Optional<Path> path = key == null ? Optional.<Path>empty() : server.getPath(key);
		if (path.isEmpty()) return respondOrClose(ctx, STATUS_404, 0, null, null, keepAlive);

		Path file = path.get();
		long total;
		try {
			total = Files.size(file);
		} catch (IOException e) {
			return respondOrClose(ctx, STATUS_404, 0, null, null, keepAlive);
		}

		// Objects already are their hash. A document is only hashed when a validator actually asks, keeping the SHA-1
		// of a possibly large journal off the event loop for the plain GETs; the response then simply carries no ETag.
		boolean document = key.equals(GenerationHosting.HEAD_DOCUMENT_KEY) || key.equals(GenerationHosting.JOURNAL_KEY);
		String etag = document ? null : key;
		if (ifNoneMatch != null) {
			etag = document ? HashUtils.getHash(file) : key;
			if (etag == null) return respondOrClose(ctx, STATUS_404, 0, null, null, keepAlive);
		}

		if (ifNoneMatch != null && (ifNoneMatch.equals(etag) || ifNoneMatch.equals("\"" + etag + "\""))) {
			return respondOrClose(ctx, STATUS_304, 0, etag, null, keepAlive);
		}

		ByteRange byteRange = range == null ? null : parseRange(range, total);
		if (byteRange != null && !byteRange.satisfiable) {
			return respondOrClose(ctx, STATUS_416, 0, etag, "bytes */" + total, keepAlive);
		}

		long offset = byteRange == null ? 0 : byteRange.start;
		long length = byteRange == null ? total : byteRange.endInclusive - byteRange.start + 1;
		String status = byteRange == null ? STATUS_200 : STATUS_206;
		String contentRange = byteRange == null ? null : "bytes " + byteRange.start + "-" + byteRange.endInclusive + "/" + total;

		if (length == 0) return respondOrClose(ctx, status, 0, etag, contentRange, keepAlive);

		FileChannel channel = null;
		try {
			channel = FileChannel.open(file, StandardOpenOption.READ);
			channel.position(offset);
		} catch (IOException e) {
			closeQuietly(channel);
			return respondOrClose(ctx, STATUS_404, 0, null, null, keepAlive);
		}

		// The head is in flight from here on, so the only honest completion of a mid-stream failure is dropping the connection.
		ChannelFuture headWritten = ctx.writeAndFlush(response(status, length, etag, contentRange));
		streaming = true;
		final FileChannel opened = channel;
		final boolean responseKeepAlive = keepAlive;
		try {
			senders.execute(() -> streamBody(ctx, opened, length, headWritten, responseKeepAlive));
		} catch (RejectedExecutionException rejected) {
			closeQuietly(opened);
			ctx.close();
		}
		return false;
	}

	/** Writes one bodyless response, honoring a client's close preference on responses that would otherwise keep the connection open. */
	private boolean respondOrClose(ChannelHandlerContext ctx, String status, long contentLength, String etag, String contentRange, boolean keepAlive) {
		if (keepAlive) {
			respond(ctx, status, contentLength, etag, contentRange);
			return true;
		}
		respondThenClose(ctx, status, contentLength, etag, contentRange);
		return false;
	}

	private void streamBody(ChannelHandlerContext ctx, FileChannel file, long length, ChannelFuture headWritten, boolean keepAlive) {
		Channel channel = ctx.channel();
		Throwable failure = awaitWritten(channel, headWritten);
		if (failure == null && !headWritten.isSuccess()) failure = causeOf(headWritten);
		long sent = 0;
		try {
			while (failure == null && sent < length) {
				int chunkLength = (int) Math.min(DEFAULT_CHUNK_SIZE, length - sent);
				ByteBuf chunk = channel.alloc().heapBuffer(chunkLength, chunkLength);
				// Owned by the write once handed to writeAndFlush; until then every exit must release it.
				try {
					ByteBuffer buffer = chunk.nioBuffer(0, chunkLength);
					int read = fill(file, buffer);
					if (read <= 0) {
						failure = new IOException("File ended before the response was fully streamed");
						chunk.release();
						break;
					}
					chunk.writerIndex(read);
					sent += read;
				} catch (Exception e) {
					chunk.release();
					throw e;
				}
				ChannelFuture written = channel.writeAndFlush(chunk);
				failure = awaitWritten(channel, written);
				if (failure == null && !written.isSuccess()) failure = causeOf(written);
			}
			if (failure == null && sent < length) failure = new IOException("File ended before the response was fully streamed");
		} catch (Exception e) {
			failure = e;
		} finally {
			closeQuietly(file);
		}

		Throwable finalFailure = failure;
		executeOnLoop(channel, () -> {
			streaming = false;
			if (finalFailure != null) {
				// Body bytes are already in flight: an error status cannot follow them, the log is the only receipt.
				LOGGER.error("HTTP response of {} bytes failed: {}", length, finalFailure.getMessage(), finalFailure);
				channel.close();
			} else if (keepAlive && channel.isActive()) {
				serveLoop(ctx);
			} else {
				channel.close();
			}
		});
	}

	/**
	 * Single-range support only: {@code bytes=X-} and {@code bytes=X-Y}. Multi-part, suffix ({@code bytes=-N}) and
	 * malformed specs return null (serve full), a syntactically valid spec beyond the file returns unsatisfiable (416).
	 */
	private static ByteRange parseRange(String header, long total) {
		if (!header.startsWith("bytes=")) return null;
		String spec = header.substring("bytes=".length()).trim();
		if (spec.indexOf(',') >= 0) return null;
		int dash = spec.indexOf('-');
		if (dash < 0) return null;
		String first = spec.substring(0, dash).trim();
		String last = spec.substring(dash + 1).trim();
		if (first.isEmpty()) return null;
		long start;
		try {
			start = Long.parseLong(first);
		} catch (NumberFormatException e) {
			return null;
		}
		if (start < 0) return null;
		if (start >= total) return new ByteRange(0, 0, false);
		long end = total - 1;
		if (!last.isEmpty()) {
			try {
				end = Long.parseLong(last);
			} catch (NumberFormatException e) {
				return null;
			}
			if (end < start) return null;
			if (end > total - 1) end = total - 1;
		}
		return new ByteRange(start, end, true);
	}

	private record ByteRange(long start, long endInclusive, boolean satisfiable) {}

	/** The route table is the URL contract: the two document names and the content-addressed objects. */
	private static String routeKey(String target) {
		if (target.equals("/" + GenerationHosting.HEAD_DOCUMENT_KEY)) return GenerationHosting.HEAD_DOCUMENT_KEY;
		if (target.equals("/" + GenerationHosting.JOURNAL_KEY)) return GenerationHosting.JOURNAL_KEY;
		if (target.startsWith("/objects/")) {
			String sha1 = target.substring("/objects/".length());
			return HashUtils.isSha1(sha1) ? HashUtils.normalizeSha1(sha1) : null;
		}
		return null;
	}

	private static int headerEnd(ByteBuf buffer) {
		int readable = buffer.readableBytes();
		if (readable < 4) return -1;
		int last = buffer.writerIndex() - 4;
		for (int index = buffer.readerIndex(); index <= last; index++) {
			if (buffer.getByte(index) != '\r') continue;
			if (buffer.getByte(index + 1) == '\n' && buffer.getByte(index + 2) == '\r' && buffer.getByte(index + 3) == '\n') return index - buffer.readerIndex();
		}
		return -1;
	}

	private void respond(ChannelHandlerContext ctx, String status, long contentLength, String etag, String contentRange) {
		ctx.writeAndFlush(response(status, contentLength, etag, contentRange));
	}

	private void respondThenClose(ChannelHandlerContext ctx, String status, long contentLength, String etag, String contentRange) {
		ctx.writeAndFlush(response(status, contentLength, etag, contentRange)).addListener(ChannelFutureListener.CLOSE);
	}

	private static ByteBuf response(String status, long contentLength, String etag, String contentRange) {
		StringBuilder head = new StringBuilder(160);
		head.append("HTTP/1.1 ").append(status).append("\r\n");
		head.append("Content-Length: ").append(contentLength).append("\r\n");
		head.append("Content-Type: ").append(CONTENT_TYPE).append("\r\n");
		if (etag != null) head.append("ETag: \"").append(etag).append("\"\r\n");
		if (contentRange != null) head.append("Content-Range: ").append(contentRange).append("\r\n");
		head.append("\r\n");
		return Unpooled.wrappedBuffer(head.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** FileSend's stall window: a peer that stops draining cannot pin the connection past the timeout without progress. */
	private static Throwable awaitWritten(Channel channel, ChannelFuture written) {
		long stallWindowNanos = TRANSFER_WRITE_STALL_TIMEOUT.toNanos();
		long progressDeadline = System.nanoTime() + stallWindowNanos;
		long lastPending = -1;
		try {
			while (!written.await(1, TimeUnit.SECONDS)) {
				long pending = channel.isWritable() ? 0 : channel.bytesBeforeWritable();
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
}
