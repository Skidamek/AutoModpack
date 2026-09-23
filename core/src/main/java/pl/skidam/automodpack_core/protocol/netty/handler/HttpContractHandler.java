package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.serverConfig;
import static pl.skidam.automodpack_core.protocol.NetUtils.STREAM_WRITE_BYTES;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.Future;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.WireCodec;
import pl.skidam.automodpack_core.protocol.netty.ActivityTracker;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Serves the URL contract (GET /head, GET /journal, GET /objects/<sha1>) over an already-TLS-terminated pipeline with
 * hand-rolled HTTP/1.1: one in-flight response per connection, keep-alive by default. Requests pipelined while a body
 * streams are held - the first request block keeps the hostile-junk 8 KiB cap, while held heads get a 512 KiB cap that
 * fits the client's deep pipeline - and served strictly in order, so a connection never serves two bodies at once and,
 * unlike the custom protocol, needs no per-connection in-flight transfer cap: one slot per connection by
 * construction. Our own client pipelines every lane this deep.
 */
public class HttpContractHandler extends ChannelInboundHandlerAdapter {

	/** Tripwire past any real request header block; an honest client holds ≤ 8 requests ≈ 2 KB of headers in flight, 4× under this cap. Only a broken client touches it. */
	private static final int MAX_HEADER_BLOCK_BYTES = 8 * 1024;

	// The cap on request heads held while a response body streams: 2048 pipelined heads at ~250 bytes each need
	// ~500 KiB, so the client's whole count-tripwire-deep pipeline (NetUtils.PIPELINE_MAX_REQUESTS) fits with margin.
	// The hostile-junk bound above is untouched - it still governs the first request block - so a flood accumulates
	// no more than before until a legitimate body is being streamed.
	private static final int MAX_HELD_REQUEST_BYTES = 512 * 1024;

	// Stream-compression gauge: one read's worth of file input per task; the compressor's output drains as one chunked
	// frame per task, so a response's resident encoding state is one input chunk plus one output frame.
	private static final int COMPRESS_INPUT_CHUNK = 256 * 1024;
	// The drain fuse ticks this often; a peer that completes nothing for stallSeconds is declared gone.
	private static final long FUSE_TICK_SECONDS = 15;
	private static final byte[] CRLF = {'\r', '\n'};
	private static final byte[] FINAL_CHUNK = {'0', '\r', '\n', '\r', '\n'};

	// IMF-fixdate for the Date header, reformatted only when the wall-clock second moves; the pair is racy across event
	// loops but a response at worst carries a date one second old.
	private static final DateTimeFormatter HTTP_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ROOT).withZone(ZoneOffset.UTC);
	private static volatile long httpDateSecond = -1;
	private static volatile String httpDateValue = "";

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
	private final Executor diskReads;
	private final long fuseTickSeconds;
	private final long stallSeconds;
	private final ActivityTracker tracker;
	private ByteBuf cumulation;
	// The one response body currently streaming; the connection drops must end its span even when complete never runs.
	private ActivityTracker.Span inFlightSpan;
	// Same body as a state object: the loop writes from it when the channel turns writable, and the fuse watches it.
	private StreamedBody activeStream;
	private int responsesServed;
	private long bytesServed;

	public HttpContractHandler(NettyServer server, Executor diskReads) {
		this(server, diskReads, FUSE_TICK_SECONDS, TRANSFER_WRITE_STALL_TIMEOUT.toSeconds());
	}

	HttpContractHandler(NettyServer server, Executor diskReads, long fuseTickSeconds, long stallSeconds) {
		this.server = server;
		this.diskReads = diskReads;
		this.fuseTickSeconds = fuseTickSeconds;
		this.stallSeconds = stallSeconds;
		this.tracker = server.activityTracker();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (!(msg instanceof ByteBuf input)) {
			ctx.fireChannelRead(msg);
			return;
		}

		try {
			if (activeStream != null) {
				// A pipelining client's next requests wait until the current body drains; the held cap keeps the hold bounded.
				accumulate(input);
				if (cumulation.readableBytes() > MAX_HELD_REQUEST_BYTES) rejectUnparseable(ctx, MAX_HELD_REQUEST_BYTES);
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
	public void channelWritabilityChanged(ChannelHandlerContext ctx) {
		// The watermark turning writable is the stream scheduler's tick: whatever chunk the reader already produced
		// goes out here, and the next read is dispatched behind it.
		if (activeStream != null) activeStream.pump();
		ctx.fireChannelWritabilityChanged();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		LOGGER.info("HTTP contract connection closed: responses={} bytes={} streaming={} pendingHeaders={}B", responsesServed, bytesServed, activeStream != null,
				cumulation == null ? 0 : cumulation.readableBytes());
		if (activeStream != null) activeStream.discard();
		if (inFlightSpan != null) tracker.completeDropped(inFlightSpan);
		releaseCumulation();
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		if (activeStream != null) activeStream.discard();
		releaseCumulation();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		LOGGER.error("The HTTP contract connection failed; closing it", cause);
		ctx.close();
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		// The pipeline's all-idle reap: a silent connection holds a public FD for nothing. A streaming response writes
		// a chunk well inside this window at the receipted drain floor, so the event can only fire for a connection
		// with no request in flight and no body draining.
		if (evt instanceof IdleStateEvent idle) {
			LOGGER.info("HTTP contract connection went idle ({}); closing it. streaming={} responses={}", idle.state(), activeStream != null, responsesServed);
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
		while (ctx.channel().isActive() && activeStream == null && cumulation != null) {
			int headerEnd = headerEnd(cumulation);
			// The cap binds the block whether or not a terminator has arrived: junk-then-terminator streams must find no
			// richer welcome than an unterminated trickle.
			if (headerEnd < 0 || headerEnd > MAX_HEADER_BLOCK_BYTES) {
				if (headerEnd > MAX_HEADER_BLOCK_BYTES || cumulation.readableBytes() > MAX_HEADER_BLOCK_BYTES) rejectUnparseable(ctx, MAX_HEADER_BLOCK_BYTES);
				return;
			}
			if (!handleRequest(ctx, headerEnd)) return;
		}
	}

	private void rejectUnparseable(ChannelHandlerContext ctx, int capBytes) {
		LOGGER.warn("HTTP request header block exceeded {} bytes (streaming={}, pending={}B); closing the connection", capBytes, activeStream != null,
				cumulation == null ? 0 : cumulation.readableBytes());
		ctx.close();
	}

	private boolean rejectGarbage(ChannelHandlerContext ctx, ActivityTracker.Span span) {
		LOGGER.warn("Unparseable HTTP request; closing the connection");
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
		boolean http11 = requestLine[2].equals("HTTP/1.1");
		boolean keepAlive = http11;
		boolean hostSeen = false;
		String ifNoneMatch = null;
		String range = null;
		String authorization = null;
		String acceptEncoding = null;
		for (int i = 1; i < lines.length; i++) {
			String line = lines[i];
			char first = line.isEmpty() ? 0 : line.charAt(0);
			if (first == ' ' || first == '\t') return finishBodyless(ctx, span, STATUS_400, 0, null, null, false); // obs-fold continuation
			int colon = line.indexOf(':');
			if (colon <= 0) continue;
			String name = line.substring(0, colon);
			char nameEnd = name.charAt(name.length() - 1);
			if (nameEnd == ' ' || nameEnd == '\t') return finishBodyless(ctx, span, STATUS_400, 0, null, null, false); // whitespace between field name and colon
			name = name.toLowerCase(Locale.ROOT);
			String value = line.substring(colon + 1).trim();
			if (name.equals("if-none-match")) ifNoneMatch = value;
			else if (name.equals("range")) range = value;
			else if (name.equals("authorization")) authorization = value;
			else if (name.equals("accept-encoding")) acceptEncoding = value;
			else if (name.equals("host")) hostSeen = true;
			else if (name.equals("connection")) {
				for (String token : value.toLowerCase(Locale.ROOT).split(",")) {
					String option = token.trim();
					if (option.equals("close")) keepAlive = false;
					else if (option.equals("keep-alive")) keepAlive = true;
				}
			}
		}

		// RFC 9112: an HTTP/1.1 request without a Host header is invalid; HTTP/1.0 predates the requirement.
		if (http11 && !hostSeen) return finishBodyless(ctx, span, STATUS_400, 0, null, null, false);

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

		// Objects already are their hash. A document's validator etag comes from the server's memo, keeping the SHA-1
		// of a possibly large journal off the event loop for every conditional fetch; a plain GET carries no ETag.
		boolean document = key.equals(GenerationHosting.HEAD_DOCUMENT_KEY) || key.equals(GenerationHosting.JOURNAL_KEY);
		String etag = document ? null : key;
		if (ifNoneMatch != null) {
			etag = document ? server.documentEtag(file) : key;
			if (etag == null) return finishBodyless(ctx, span, STATUS_404, 0, null, null, keepAlive);
		}

		if (ifNoneMatch != null && ifNoneMatchMatches(ifNoneMatch, etag)) {
			// The 304 head states the length a 200 would have sent (RFC 9110); nothing is served, so the books stay at zero.
			tracker.complete(span, 304, 0);
			responsesServed++;
			return respondOrClose(ctx, STATUS_304, total, etag, null, keepAlive);
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

		// Plain negotiation: a codec was offered and known, so the body - whole or ranged - goes out encoded; no header,
		// an unknown or q-zeroed offer, or HTTP/1.0 (which has no Transfer-Encoding) means identity. The resume contract
		// lives in Content-Range and is untouched by the coding. Encoding stays on for objects by measurement, not by
		// faith: zstd -3 over 183 real mod/loader jars (188 MiB) gives 1.16x (gzip 1.13x), worth ~14% of the served
		// bytes on the home uplinks and relays this server targets, while compression CPU (~500 MB/s) sits three orders
		// of magnitude past any drain rate it can ever wait on.
		if (http11 && acceptEncoding != null && WireCodec.negotiate(acceptEncoding) != null) {
			return serveNegotiated(ctx, file, offset, length, total, status, etag, contentRange, keepAlive, span, acceptEncoding);
		}
		return serveIdentity(ctx, file, offset, length, total, status, etag, contentRange, keepAlive, span);
	}

	/** Ends a bodyless response: the tracker entry closes with the status before the head goes out. */
	private boolean finishBodyless(ChannelHandlerContext ctx, ActivityTracker.Span span, String status, long contentLength, String etag, String contentRange, boolean keepAlive) {
		tracker.complete(span, statusNumber(status), contentLength);
		responsesServed++;
		bytesServed += contentLength;
		return respondOrClose(ctx, status, contentLength, etag, contentRange, keepAlive);
	}

	private static int statusNumber(String status) {
		return Integer.parseInt(status.substring(0, 3));
	}

	/** RFC 7232 weak comparison: any listed validator (or {@code *}) matches; the quotes and an optional {@code W/} are never part of the opaque value. */
	private static boolean ifNoneMatchMatches(String header, String opaque) {
		for (String part : header.split(",")) {
			String candidate = part.trim();
			if (candidate.equals("*")) return true;
			if (candidate.startsWith("W/")) candidate = candidate.substring(2).trim();
			if (candidate.length() >= 2 && candidate.startsWith("\"") && candidate.endsWith("\"")) candidate = candidate.substring(1, candidate.length() - 1);
			if (candidate.equals(opaque)) return true;
		}
		return false;
	}

	/** The plain body path: objects, ranged responses, and documents for clients that did not offer zstd. */
	private boolean serveIdentity(ChannelHandlerContext ctx, Path file, long offset, long length, long total, String status, String etag, String contentRange, boolean keepAlive, ActivityTracker.Span span) {
		inFlightSpan = span;
		activeStream = new StreamedBody(ctx, file, null, null, offset, length, total, keepAlive, span, status, etag, contentRange);
		activeStream.openThenStream();
		return false;
	}

	/**
	 * A negotiated body streams: the head goes out framed as chunked (the encoded length is unknowable before the body
	 * exists), and compression runs one frame ahead of the wire under the same stall window as any streamed body.
	 * Nothing buffers a whole response, so there is no cap to hit.
	 */
	private boolean serveNegotiated(ChannelHandlerContext ctx, Path file, long offset, long length, long total, String status, String etag, String contentRange, boolean keepAlive,
			ActivityTracker.Span span, String acceptEncoding) {
		WireCodec codec = WireCodec.negotiate(acceptEncoding);
		ChannelFuture headWritten = ctx.channel().writeAndFlush(chunkedResponse(status, contentRange, etag, codec));
		inFlightSpan = span;
		activeStream = new StreamedBody(ctx, file, null, codec, offset, length, total, keepAlive, span, status, etag, contentRange);
		activeStream.start(headWritten);
		return false;
	}

	/**
	 * One streamed response. The event loop writes a buffer whenever the channel is writable and never blocks: file
	 * reads and compression run on the bounded reader pool, which keeps at most two buffers in flight - one draining
	 * on the wire, one being read or compressed - so the disk hides behind the wire and a slow client parks no thread.
	 * The fuse guards the drain: a scheduled check compares the completed-write counter every {@code fuseTickSeconds},
	 * and a peer that completes nothing for {@code stallSeconds} is declared gone - a draining peer resets it with
	 * every chunk, so only a silently stopped one trips. Every field here is loop-confined except the file channel,
	 * the compressor and the frame buffer, which only the serialized read tasks touch; the executor handoffs in
	 * between publish them.
	 */
	private final class StreamedBody {
		private final ChannelHandlerContext ctx;
		private final Path path;
		private final WireCodec codec; // null streams identity with an exact Content-Length
		private final String etag;
		private final String contentRange;
		private final long offset;
		private final long length;
		private final long expectedTotal; // the stat the head promised; the open must agree or the stream fails
		private final boolean keepAlive;
		private final ActivityTracker.Span span;
		private final String status;

		private FileChannel file; // opened by the caller for identity, by the first read for negotiated
		private OutputStream compressor; // the codec's continuous stream; read tasks only
		private ByteArrayOutputStream frames; // its sink, drained on every emitted frame; read tasks only
		private long flushed; // compressed wire bytes emitted so far; read tasks only
		private long readPosition; // loop mirror of the file cursor
		private long fileRemaining; // loop mirror of the file bytes left
		private long sent; // wire bytes whose writes have completed
		private long completed; // the fuse's completed-write counter
		private long fuseMark;
		private long stallDeadlineNanos = Long.MAX_VALUE;
		private int stallTicks;
		private int draining; // buffers handed to the socket and not yet fully written
		private boolean reading; // a read task is queued or running
		private boolean eof; // the file has no bytes left
		private boolean done; // finished or failed; every later callback is ignored
		private boolean closed; // the file channel has been closed
		private ScheduledFuture<?> fuse;

		StreamedBody(ChannelHandlerContext ctx, Path path, FileChannel file, WireCodec codec, long offset, long length, long expectedTotal, boolean keepAlive, ActivityTracker.Span span, String status, String etag,
				String contentRange) {
			this.ctx = ctx;
			this.path = path;
			this.file = file;
			this.codec = codec;
			this.etag = etag;
			this.contentRange = contentRange;
			this.offset = offset;
			this.length = length;
			this.expectedTotal = expectedTotal;
			this.fileRemaining = length;
			this.keepAlive = keepAlive;
			this.span = span;
			this.status = status;
		}

		/** Starts streaming once the negotiated head is on the wire; a failed head ends the response before any body byte. */
		void start(ChannelFuture headWritten) {
			armFuse();
			headWritten.addListener(future -> {
				if (future.isSuccess()) pump();
				else fail(causeOf(future));
			});
		}

		/** Opens the file off the loop, then writes the head and streams - an unopenable object still answers 404, since the head carries no body bytes yet. */
		void openThenStream() {
			diskReads.execute(() -> {
				try {
					file = FileChannel.open(path, StandardOpenOption.READ);
					if (file.size() != expectedTotal) {
						// A publish swapped the file between the stat and the open; serving would mix generations, so die loudly and let the client retry into the new one.
						ctx.executor().execute(() -> fail(new IOException("The file changed size between the stat and the open: expected " + expectedTotal + " bytes")));
						return;
					}
					file.position(offset);
					ctx.executor().execute(this::writeHeadAndPump);
				} catch (Throwable openFailure) {
					ctx.executor().execute(() -> {
						closeFile();
						tracker.complete(span, 404, 0);
						respondOrClose(ctx, STATUS_404, 0, null, null, keepAlive);
						activeStream = null;
					});
				}
			});
		}

		// The head is in flight from here on, so the only honest completion of a mid-stream failure is dropping the connection.
		private void writeHeadAndPump() {
			armFuse();
			// A body whose connection closes when it completes announces the close, so a pipelining client knows its queued request is lost.
			ChannelFuture headWritten = ctx.writeAndFlush(response(status, length, etag, contentRange, null, !keepAlive));
			headWritten.addListener(future -> {
				if (future.isSuccess()) pump();
				else fail(causeOf(future));
			});
		}

		private void armFuse() {
			stallDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(stallSeconds);
			fuse = ctx.executor().scheduleWithFixedDelay(this::checkFuse, fuseTickSeconds, fuseTickSeconds, TimeUnit.SECONDS);
		}

		/** Dispatches a read while the depth window has room; runs on the event loop with every other state change. */
		private void pump() {
			if (done || eof || reading || draining >= 2 || !ctx.channel().isWritable()) return;
			reading = true;
			long position = offset + (length - fileRemaining);
			long remaining = fileRemaining;
			diskReads.execute(() -> read(position, remaining));
		}

		/** Reads and encodes one chunk off the loop; reports it back to the loop, which owns every field touched there. */
		private void read(long position, long remaining) {
			ByteBuf out = null;
			try {
				if (file == null) {
					file = FileChannel.open(path, StandardOpenOption.READ);
					if (file.size() != expectedTotal) throw new IOException("The file changed size between the stat and the open: expected " + expectedTotal + " bytes");
					file.position(offset);
				}
				if (codec == null) {
					int chunk = (int) Math.min((long) STREAM_WRITE_BYTES, remaining);
					out = ctx.alloc().heapBuffer(chunk, chunk);
					int read = fill(file, out.nioBuffer(0, chunk));
					if (read != chunk) throw new IOException("File ended before the response was fully streamed");
					out.writerIndex(read);
					boolean last = remaining == chunk;
					ByteBuf finalOut = out;
					ctx.executor().execute(() -> deliver(finalOut, chunk, last, chunk));
					return;
				}
				if (compressor == null) {
					// One continuous zstd stream per response: the ratio receipt assumes the whole file shares a dictionary.
					frames = new ByteArrayOutputStream(STREAM_WRITE_BYTES);
					compressor = codec.wrap(frames);
				}
				long consumed = 0;
				boolean last = false;
				do {
					int chunk = (int) Math.min((long) COMPRESS_INPUT_CHUNK, remaining - consumed);
					ByteBuffer input = ByteBuffer.allocate(chunk);
					int read = file.read(input, position + consumed);
					if (read < 0) throw new IOException("File ended before the response was fully streamed");
					compressor.write(input.array(), 0, read);
					consumed += read;
					if (consumed == remaining) {
						last = true;
						compressor.close(); // flushes the encoder's tail into the frame buffer
					}
					tracker.progress(span, flushed);
				} while (!last && frames.size() < STREAM_WRITE_BYTES);
				byte[] frame = frames.toByteArray();
				frames.reset();
				flushed += frame.length;
				byte[] sizeLine = (Integer.toHexString(frame.length) + "\r\n").getBytes(StandardCharsets.US_ASCII);
				if (last) {
					CompositeByteBuf composite = ctx.alloc().compositeBuffer();
					composite.addComponent(true, Unpooled.wrappedBuffer(sizeLine));
					composite.addComponent(true, Unpooled.wrappedBuffer(frame));
					composite.addComponent(true, Unpooled.wrappedBuffer(CRLF));
					composite.addComponent(true, Unpooled.wrappedBuffer(FINAL_CHUNK));
					out = composite;
				} else {
					out = Unpooled.wrappedBuffer(sizeLine, frame, CRLF);
				}
				ByteBuf finalOut = out;
				long consumedTotal = consumed;
				boolean finalSegment = last;
				long wireBytes = sizeLine.length + frame.length + CRLF.length + (last ? FINAL_CHUNK.length : 0);
				ctx.executor().execute(() -> deliver(finalOut, consumedTotal, finalSegment, wireBytes));
			} catch (Throwable readFailure) {
				if (out != null) out.release();
				ctx.executor().execute(() -> fail(readFailure));
			}
		}

		/** Writes one delivered chunk; its completion is both the fuse's progress and the depth window's refill. */
		private void deliver(ByteBuf out, long consumed, boolean last, long wireBytes) {
			if (done) {
				out.release();
				return;
			}
			fileRemaining -= consumed;
			if (last) eof = true;
			reading = false;
			draining++;
			ctx.writeAndFlush(out).addListener(future -> {
				completed += wireBytes;
				sent += wireBytes;
				draining--;
				// Identity's one progress report, taken where sent is exact; negotiated reports from its read loop instead.
				if (codec == null) tracker.progress(span, sent);
				if (done) return;
				if (!future.isSuccess()) {
					fail(causeOf(future));
					return;
				}
				if (eof && draining == 0) finish();
				else pump();
			});
			pump();
		}

		private void checkFuse() {
			if (done) return;
			long now = System.nanoTime();
			if (completed > fuseMark) {
				fuseMark = completed;
				stallDeadlineNanos = now + TimeUnit.SECONDS.toNanos(stallSeconds);
				return;
			}
			if (now >= stallDeadlineNanos) fail(new IOException("Write stalled: the peer stopped draining the connection"));
		}

		private void finish() {
			complete(null);
		}

		private void fail(Throwable failure) {
			if (done) return;
			LOGGER.error("The streamed response to {} died mid-body ({} bytes sent)", span.routeKey, sent, failure);
			complete(failure);
		}

		private void complete(Throwable failure) {
			done = true;
			if (fuse != null) fuse.cancel(false);
			tracker.complete(span, statusNumber(status), sent);
			responsesServed++;
			bytesServed += sent;
			closeFile();
			activeStream = null;
			if (failure == null && keepAlive && ctx.channel().isActive()) serveLoop(ctx);
			else ctx.channel().close();
		}

		/** Disconnect cleanup: everything the loop can no longer drive goes away with the channel. */
		private void discard() {
			done = true;
			if (fuse != null) fuse.cancel(false);
			closeFile();
		}

		private void closeFile() {
			if (closed) return;
			closed = true;
			closeQuietly(file);
			file = null;
		}
	}

	/** The negotiated head: no length exists yet, so the body is framed chunked, the coding is named, and a range keeps its Content-Range. */
	private static ByteBuf chunkedResponse(String status, String contentRange, String etag, WireCodec codec) {
		StringBuilder head = new StringBuilder(192);
		head.append("HTTP/1.1 ").append(status).append("\r\n");
		head.append("Date: ").append(httpDate()).append("\r\n");
		head.append("Content-Type: ").append(CONTENT_TYPE).append("\r\n");
		if (etag != null) head.append("ETag: \"").append(etag).append("\"\r\n");
		if (contentRange != null) head.append("Content-Range: ").append(contentRange).append("\r\n");
		head.append("Content-Encoding: ").append(codec.wireName()).append("\r\n").append("Vary: Accept-Encoding\r\n");
		head.append("Transfer-Encoding: chunked\r\n\r\n");
		return Unpooled.wrappedBuffer(head.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** IMF-fixdate in GMT, reformatted only when the wall-clock second moves. */
	private static String httpDate() {
		long second = System.currentTimeMillis() / 1000;
		String cached = httpDateValue;
		if (second == httpDateSecond) return cached;
		String fresh = HTTP_DATE_FORMAT.format(Instant.ofEpochSecond(second));
		httpDateValue = fresh;
		httpDateSecond = second;
		return fresh;
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
		// The close is announced in the head, so a pipelining client knows its queued request is lost before we drop the connection.
		ctx.writeAndFlush(response(status, contentLength, etag, contentRange, null, true)).addListener(ChannelFutureListener.CLOSE);
	}

	private static ByteBuf response(String status, long contentLength, String etag, String contentRange) {
		return response(status, contentLength, etag, contentRange, null, false);
	}

	private static ByteBuf response(String status, long contentLength, String etag, String contentRange, String contentEncoding) {
		return response(status, contentLength, etag, contentRange, contentEncoding, false);
	}

	private static ByteBuf response(String status, long contentLength, String etag, String contentRange, String contentEncoding, boolean connectionClose) {
		StringBuilder head = new StringBuilder(192);
		head.append("HTTP/1.1 ").append(status).append("\r\n");
		head.append("Date: ").append(httpDate()).append("\r\n");
		if (STATUS_405.equals(status)) head.append("Allow: GET\r\n");
		if (connectionClose) head.append("Connection: close\r\n");
		head.append("Content-Length: ").append(contentLength).append("\r\n");
		head.append("Content-Type: ").append(CONTENT_TYPE).append("\r\n");
		if (etag != null) head.append("ETag: \"").append(etag).append("\"\r\n");
		if (contentRange != null) head.append("Content-Range: ").append(contentRange).append("\r\n");
		if (contentEncoding != null) head.append("Content-Encoding: ").append(contentEncoding).append("\r\n").append("Vary: Accept-Encoding\r\n");
		head.append("\r\n");
		return Unpooled.wrappedBuffer(head.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static Throwable causeOf(Future<?> future) {
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

}
