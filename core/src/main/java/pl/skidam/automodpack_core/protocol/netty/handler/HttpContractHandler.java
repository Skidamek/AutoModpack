package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.serverConfig;
import static pl.skidam.automodpack_core.protocol.NetUtils.DEFAULT_CHUNK_SIZE;
import static pl.skidam.automodpack_core.protocol.NetUtils.TRANSFER_WRITE_STALL_TIMEOUT;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketAddress;
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

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.WireCodec;
import pl.skidam.automodpack_core.protocol.netty.ActivityTracker;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Serves the URL contract (GET /head, GET /journal, GET /objects/<sha1>) over an already-TLS-terminated pipeline with
 * hand-rolled HTTP/1.1: one in-flight response per connection, keep-alive by default, requests arriving while a body
 * streams are held and served after it. No pipelining support; a pipelining client just gets serialized responses, so
 * unlike the custom protocol there is no per-connection in-flight transfer cap - one slot per connection by construction.
 */
public class HttpContractHandler extends ChannelInboundHandlerAdapter {

	/** Tripwire past any real request header block; an honest client holds ≤ 8 requests ≈ 2 KB of headers in flight, 4× under this cap; only a pipelining abuser touches it. */
	private static final int MAX_HEADER_BLOCK_BYTES = 8 * 1024;

	// Stream-compression gauges: the sniff sample, one frame's worth of file input, and the frame size the wire sees.
	// A 64 KiB sample decides identity vs codec for a whole file - stored content (jars, sounds, textures) sniffs at
	// ≥ 0.95 under every registry codec while text sits under 0.5 - and 256 KiB in ≈ 192 KiB out bounds a frame's
	// resident bytes to a fraction of one lane's worth of nothing next to the identity path's 4 MiB chunks.
	private static final int SNIFF_BYTES = 64 * 1024;
	private static final int COMPRESS_INPUT_CHUNK = 256 * 1024;
	private static final int FRAME_BYTES = 192 * 1024;
	private static final double INCOMPRESSIBLE_RATIO = 0.9;
	private static final byte[] CRLF = {'\r', '\n'};
	private static final byte[] FINAL_CHUNK = {'0', '\r', '\n', '\r', '\n'};

	private static final String BEARER_PREFIX = "Bearer ";

	private static final String STATUS_200 = "200 OK";
	private static final String STATUS_206 = "206 Partial Content";
	private static final String STATUS_304 = "304 Not Modified";
	private static final String STATUS_400 = "400 Bad Request";
	private static final String STATUS_401 = "401 Unauthorized";
	private static final String STATUS_404 = "404 Not Found";
	private static final String STATUS_405 = "405 Method Not Allowed";
	private static final String STATUS_416 = "416 Range Not Satisfiable";

	private static final String CONTENT_TYPE = "application/octet-stream";

	private final NettyServer server;
	private final Executor senders;
	private final ActivityTracker tracker;
	private ByteBuf cumulation;
	private volatile boolean streaming;
	// The one response body currently streaming; the connection drops must end its span even when finishStream never runs.
	private ActivityTracker.Span inFlightSpan;

	public HttpContractHandler(NettyServer server, Executor senders) {
		this.server = server;
		this.senders = senders;
		this.tracker = server.activityTracker();
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
		if (inFlightSpan != null) tracker.completeDropped(inFlightSpan);
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

	private boolean rejectGarbage(ChannelHandlerContext ctx, ActivityTracker.Span span) {
		LOGGER.debug("Unparseable HTTP request; closing the connection");
		tracker.complete(span, 400, 0);
		ctx.close();
		return false;
	}

	/** Handles one fully-buffered request head; false means the connection closes or the response body streams. */
	private boolean handleRequest(ChannelHandlerContext ctx, int headerEnd) {
		ActivityTracker.Span span = tracker.start(String.valueOf(addressOf(ctx.channel())));
		String request = cumulation.readCharSequence(headerEnd, StandardCharsets.UTF_8).toString();
		cumulation.skipBytes(4);
		cumulation.discardReadBytes();

		String[] lines = request.split("\r\n", -1);
		String[] requestLine = lines[0].split(" ");
		if (requestLine.length != 3 || (!requestLine[2].equals("HTTP/1.1") && !requestLine[2].equals("HTTP/1.0"))) return rejectGarbage(ctx, span);

		String method = requestLine[0];
		String target = requestLine[1];
		boolean keepAlive = requestLine[2].equals("HTTP/1.1");
		String ifNoneMatch = null;
		String range = null;
		String authorization = null;
		String acceptEncoding = null;
		for (int i = 1; i < lines.length; i++) {
			int colon = lines[i].indexOf(':');
			if (colon <= 0) continue;
			String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = lines[i].substring(colon + 1).trim();
			if (name.equals("if-none-match")) ifNoneMatch = value;
			else if (name.equals("range")) range = value;
			else if (name.equals("authorization")) authorization = value;
			else if (name.equals("accept-encoding")) acceptEncoding = value;
			else if (name.equals("connection")) {
				String connection = value.toLowerCase(Locale.ROOT);
				if (connection.contains("close")) keepAlive = false;
				else if (connection.contains("keep-alive")) keepAlive = true;
			}
		}

		if (serverConfig.validateSecrets && !authorized(ctx, authorization, span)) return false;

		if (!method.equals("GET")) return finishBodyless(ctx, span, STATUS_405, 0, null, null, keepAlive);

		// The contract paths carry no encoding, so a percent-encoded target cannot name a route.
		if (target.indexOf('%') >= 0) return finishBodyless(ctx, span, STATUS_400, 0, null, null, false);

		String key = routeKey(target);
		span.routeKey = key;
		Optional<Path> path = key == null ? Optional.<Path>empty() : server.getPath(key);
		if (path.isEmpty()) return finishBodyless(ctx, span, STATUS_404, 0, null, null, keepAlive);

		Path file = path.get();
		long total;
		try {
			total = Files.size(file);
		} catch (IOException e) {
			return finishBodyless(ctx, span, STATUS_404, 0, null, null, keepAlive);
		}

		// Objects already are their hash. A document is only hashed when a validator actually asks, keeping the SHA-1
		// of a possibly large journal off the event loop for the plain GETs; the response then simply carries no ETag.
		boolean document = key.equals(GenerationHosting.HEAD_DOCUMENT_KEY) || key.equals(GenerationHosting.JOURNAL_KEY) || key.equals(GenerationHosting.MUSIC_DOCUMENT_KEY);
		String etag = document ? null : key;
		if (ifNoneMatch != null) {
			etag = document ? HashUtils.getHash(file) : key;
			if (etag == null) return finishBodyless(ctx, span, STATUS_404, 0, null, null, keepAlive);
		}

		if (ifNoneMatch != null && (ifNoneMatch.equals(etag) || ifNoneMatch.equals("\"" + etag + "\""))) {
			return finishBodyless(ctx, span, STATUS_304, 0, etag, null, keepAlive);
		}

		ByteRange byteRange = range == null ? null : parseRange(range, total);
		if (byteRange != null && !byteRange.satisfiable) {
			return finishBodyless(ctx, span, STATUS_416, 0, etag, "bytes */" + total, keepAlive);
		}

		long offset = byteRange == null ? 0 : byteRange.start;
		long length = byteRange == null ? total : byteRange.endInclusive - byteRange.start + 1;
		String status = byteRange == null ? STATUS_200 : STATUS_206;
		String contentRange = byteRange == null ? null : "bytes " + byteRange.start + "-" + byteRange.endInclusive + "/" + total;

		if (length == 0) return finishBodyless(ctx, span, status, 0, etag, contentRange, keepAlive);

		// Full bodies are negotiable per request; ranges stay identity so resume offsets keep their meaning.
		if (byteRange == null && acceptEncoding != null && WireCodec.negotiate(acceptEncoding) != null) {
			return serveCompressedDocument(ctx, file, total, etag, keepAlive, span, acceptEncoding);
		}

		return serveIdentity(ctx, file, offset, length, status, etag, contentRange, keepAlive, span);
	}

	/** Ends a bodyless response: the tracker entry closes with the status before the head goes out. */
	private boolean finishBodyless(ChannelHandlerContext ctx, ActivityTracker.Span span, String status, long contentLength, String etag, String contentRange, boolean keepAlive) {
		tracker.complete(span, statusNumber(status), contentLength);
		return respondOrClose(ctx, status, contentLength, etag, contentRange, keepAlive);
	}

	private static int statusNumber(String status) {
		return Integer.parseInt(status.substring(0, 3));
	}

	/** The plain body path: objects, ranged responses, and documents for clients that did not offer zstd. */
	private boolean serveIdentity(ChannelHandlerContext ctx, Path file, long offset, long length, String status, String etag, String contentRange, boolean keepAlive, ActivityTracker.Span span) {
		FileChannel channel = null;
		try {
			channel = FileChannel.open(file, StandardOpenOption.READ);
			channel.position(offset);
		} catch (IOException e) {
			closeQuietly(channel);
			streaming = false;
			tracker.complete(span, 404, 0);
			return respondOrClose(ctx, STATUS_404, 0, null, null, keepAlive);
		}

		// The head is in flight from here on, so the only honest completion of a mid-stream failure is dropping the connection.
		ChannelFuture headWritten = ctx.writeAndFlush(response(status, length, etag, contentRange, null));
		streaming = true;
		span.totalBytes = length;
		inFlightSpan = span;
		final FileChannel opened = channel;
		final boolean responseKeepAlive = keepAlive;
		try {
			senders.execute(() -> streamBody(ctx, opened, length, headWritten, responseKeepAlive, span, status));
		} catch (RejectedExecutionException rejected) {
			closeQuietly(opened);
			tracker.complete(span, statusNumber(status), 0);
			ctx.close();
		}
		return false;
	}

	/**
	 * A negotiated body streams: a sniff decides between identity and the codec, the head goes out framed as chunked
	 * (the compressed length is unknowable before the body exists), and compression runs one frame ahead of the wire
	 * under the same stall window as any streamed body. Nothing buffers a whole response, so there is no cap to hit.
	 */
	private boolean serveCompressedDocument(ChannelHandlerContext ctx, Path file, long total, String etag, boolean keepAlive, ActivityTracker.Span span, String acceptEncoding) {
		streaming = true;
		inFlightSpan = span;
		try {
			senders.execute(() -> {
				WireCodec codec = WireCodec.negotiate(acceptEncoding);
				double ratio = sniffRatio(file, codec);
				if (ratio < 0 || ratio > INCOMPRESSIBLE_RATIO) {
					serveIdentity(ctx, file, 0, total, STATUS_200, etag, null, keepAlive, span);
					return;
				}
				streamCompressedBody(ctx, file, codec, etag, keepAlive, span);
			});
		} catch (RejectedExecutionException rejected) {
			streaming = false;
			tracker.complete(span, 200, 0);
			ctx.close();
		}
		return false;
	}

	/** The sniff receipt: compression ratio of the first {@link #SNIFF_BYTES} under the codec; -1 means the file is unreadable and identity must answer. */
	private static double sniffRatio(Path file, WireCodec codec) {
		ByteArrayOutputStream sink = new ByteArrayOutputStream(SNIFF_BYTES);
		long sampled;
		try (OutputStream compressor = codec.wrap(sink); FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer sample = ByteBuffer.allocate(SNIFF_BYTES);
			while (channel.read(sample) != -1 && sample.hasRemaining()) {
			}
			sampled = sample.position();
			compressor.write(sample.array(), 0, (int) sampled);
		} catch (IOException e) {
			LOGGER.debug("Failed to sniff {}; serving identity", file, e);
			return -1;
		}
		return sampled == 0 ? 0 : (double) sink.size() / sampled;
	}

	/** Streams the negotiated body: file through the codec into chunked frames, queued ahead of the peer's drain until the watermark pauses them. */
	private void streamCompressedBody(ChannelHandlerContext ctx, Path file, WireCodec codec, String etag, boolean keepAlive, ActivityTracker.Span span) {
		Channel channel = ctx.channel();
		ChannelFuture headWritten = channel.writeAndFlush(chunkedResponse(etag, codec));
		Throwable failure = awaitWritten(channel, headWritten);
		if (failure == null && !headWritten.isSuccess()) failure = causeOf(headWritten);
		FrameSink frames = new FrameSink(channel);
		try (FileChannel source = FileChannel.open(file, StandardOpenOption.READ); OutputStream compressor = codec.wrap(frames)) {
			ByteBuffer buffer = ByteBuffer.allocate(COMPRESS_INPUT_CHUNK);
			while (failure == null) {
				buffer.clear();
				int read = source.read(buffer);
				if (read < 0) break;
				compressor.write(buffer.array(), 0, read);
				tracker.progress(span, frames.flushed());
				frames.frameIfDue();
			}
		} catch (Exception e) {
			failure = e;
		}
		try {
			if (failure == null) {
				frames.frameRemaining();
				ChannelFuture last = channel.writeAndFlush(Unpooled.wrappedBuffer(FINAL_CHUNK));
				failure = awaitWritten(channel, last);
				if (failure == null && !last.isSuccess()) failure = causeOf(last);
			}
		} catch (IOException e) {
			failure = e;
		}
		Throwable finalFailure = failure;
		long sentBytes = frames.flushed();
		executeOnLoop(channel, () -> finishStream(ctx, span, STATUS_200, sentBytes, finalFailure, keepAlive));
	}

	/** Collects compressor output until it holds a frame's worth, then writes it as one sized chunk. Not thread-safe; owned by one sender task. */
	private final class FrameSink extends OutputStream {
		private final Channel channel;
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(FRAME_BYTES);
		private long flushed;

		FrameSink(Channel channel) {
			this.channel = channel;
		}

		long flushed() {
			return flushed;
		}

		@Override
		public void write(int b) {
			buffer.write(b);
		}

		@Override
		public void write(byte[] source, int offset, int length) {
			buffer.write(source, offset, length);
		}

		void frameIfDue() throws IOException {
			if (buffer.size() >= FRAME_BYTES) frame();
		}

		void frameRemaining() throws IOException {
			if (buffer.size() > 0) frame();
		}

		/** Emits the buffered bytes as one sized chunk; only a congested channel blocks the next compress. */
		private void frame() throws IOException {
			byte[] bytes = buffer.toByteArray();
			buffer.reset();
			flushed += bytes.length;
			byte[] sizeLine = (Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8);
			ChannelFuture written = channel.writeAndFlush(Unpooled.wrappedBuffer(sizeLine, bytes, CRLF));
			Throwable failure = awaitWhenCongested(channel, written);
			if (failure != null) throw failure instanceof IOException io ? io : new IOException(failure);
		}
	}

	/**
	 * The streaming backpressure point: a write whose channel still has queue room is left to drain while the next chunk
	 * is read or compressed; only a channel past its high watermark waits, under the same stall window as before. A
	 * channel with room cannot hide a failure for long - every later write checks the queue - and a response's final
	 * write is always awaited, which completes every write queued before it.
	 */
	private static Throwable awaitWhenCongested(Channel channel, ChannelFuture written) {
		if (written.isDone()) return written.isSuccess() ? null : causeOf(written);
		if (channel.isWritable()) return null;
		Throwable failure = awaitWritten(channel, written);
		if (failure == null && !written.isSuccess()) failure = causeOf(written);
		return failure;
	}

	/** The negotiated head: no length exists yet, so the body is framed chunked and the coding is named. */
	private static ByteBuf chunkedResponse(String etag, WireCodec codec) {
		StringBuilder head = new StringBuilder(160);
		head.append("HTTP/1.1 ").append(STATUS_200).append("\r\n");
		head.append("Content-Type: ").append(CONTENT_TYPE).append("\r\n");
		if (etag != null) head.append("ETag: \"").append(etag).append("\"\r\n");
		head.append("Content-Encoding: ").append(codec.wireName()).append("\r\n").append("Vary: Accept-Encoding\r\n");
		head.append("Transfer-Encoding: chunked\r\n\r\n");
		return Unpooled.wrappedBuffer(head.toString().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Bearer-secret gate on every request: the login-issued (or provisioning) secret rides per request, so instant
	 * revocation and expiry apply to the next request without touching the connection. A failed validation never
	 * releases the connection.
	 */
	private boolean authorized(ChannelHandlerContext ctx, String authorization, ActivityTracker.Span span) {
		SocketAddress address = addressOf(ctx.channel());
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX) || authorization.length() == BEARER_PREFIX.length()) {
			LOGGER.warn("Rejecting a modpack download request from {} without a bearer secret", address);
			rejectUnauthorized(ctx, span);
			return false;
		}
		String secret = authorization.substring(BEARER_PREFIX.length());
		if (!Secrets.isSecretValid(secret, address)) {
			rejectUnauthorized(ctx, span);
			return false;
		}
		var issued = SecretsStore.getHostSecret(secret);
		span.actor = issued == null ? null : issued.getValue().name();
		return true;
	}

	private static SocketAddress addressOf(Channel channel) {
		SocketAddress real = channel.attr(NettyServer.REAL_REMOTE_ADDR).get();
		return real != null ? real : channel.remoteAddress();
	}

	private void rejectUnauthorized(ChannelHandlerContext ctx, ActivityTracker.Span span) {
		tracker.complete(span, 401, 0);
		respondThenClose(ctx, STATUS_401, 0, null, null);
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

	private void streamBody(ChannelHandlerContext ctx, FileChannel file, long length, ChannelFuture headWritten, boolean keepAlive, ActivityTracker.Span span, String status) {
		Channel channel = ctx.channel();
		Throwable failure = awaitWritten(channel, headWritten);
		if (failure == null && !headWritten.isSuccess()) failure = causeOf(headWritten);
		long sent = 0;
		ChannelFuture lastWritten = headWritten;
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
				lastWritten = channel.writeAndFlush(chunk);
				tracker.progress(span, sent);
				failure = awaitWhenCongested(channel, lastWritten);
			}
			if (failure == null && sent < length) failure = new IOException("File ended before the response was fully streamed");
			// The queued chunks ahead of the last one complete with it; only here does the whole body count as delivered.
			if (failure == null) {
				failure = awaitWritten(channel, lastWritten);
				if (failure == null && !lastWritten.isSuccess()) failure = causeOf(lastWritten);
			}
		} catch (Exception e) {
			failure = e;
		} finally {
			closeQuietly(file);
		}

		Throwable finalFailure = failure;
		long sentBytes = sent;
		executeOnLoop(channel, () -> finishStream(ctx, span, status, sentBytes, finalFailure, keepAlive));
	}

	private void finishStream(ChannelHandlerContext ctx, ActivityTracker.Span span, String status, long bytesSent, Throwable failure, boolean keepAlive) {
		Channel channel = ctx.channel();
		inFlightSpan = null;
		tracker.complete(span, statusNumber(status), bytesSent);
		if (failure != null) {
			// Body bytes are already in flight: an error status cannot follow them, the log is the only receipt.
			LOGGER.error("HTTP response of {} bytes failed: {}", bytesSent, failure.getMessage(), failure);
			channel.close();
			return;
		}
		streaming = false;
		if (keepAlive && channel.isActive()) {
			serveLoop(ctx);
		} else {
			channel.close();
		}
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
		if (target.equals("/" + GenerationHosting.MUSIC_DOCUMENT_KEY)) return GenerationHosting.MUSIC_DOCUMENT_KEY;
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
		return response(status, contentLength, etag, contentRange, null);
	}

	private static ByteBuf response(String status, long contentLength, String etag, String contentRange, String contentEncoding) {
		StringBuilder head = new StringBuilder(160);
		head.append("HTTP/1.1 ").append(status).append("\r\n");
		head.append("Content-Length: ").append(contentLength).append("\r\n");
		head.append("Content-Type: ").append(CONTENT_TYPE).append("\r\n");
		if (etag != null) head.append("ETag: \"").append(etag).append("\"\r\n");
		if (contentRange != null) head.append("Content-Range: ").append(contentRange).append("\r\n");
		if (contentEncoding != null) head.append("Content-Encoding: ").append(contentEncoding).append("\r\n").append("Vary: Accept-Encoding\r\n");
		head.append("\r\n");
		return Unpooled.wrappedBuffer(head.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** The transfer stall window: a peer that stops draining cannot pin the connection past the timeout without progress. */
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
			// The loop is shutting down with its channel; there is no stream left to finish.
		}
	}
}
