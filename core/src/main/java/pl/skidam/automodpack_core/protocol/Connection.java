package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.DEFAULT_CHUNK_SIZE;
import static pl.skidam.automodpack_core.protocol.NetUtils.USER_AGENT;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

import javax.net.ssl.SSLSocket;

import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * One pooled TLS connection speaking minimal HTTP/1.1 against the modpack contract: {@code GET /<document>} and
 * {@code GET /objects/<sha1>}. Up to {@link #PIPELINE_DEPTH} requests sit in flight, written as the pool hands out
 * slots, and one reader per connection consumes the responses strictly in order; a failure anywhere fails every
 * pending request, because alignment is lost and every request is an idempotent GET whose retry belongs to the
 * download manager. Bodies arrive Content-Length framed, chunked (our server streams negotiated bodies that way), or
 * close framed; content codings decode through the {@link WireCodec} registry. The parser survives foreign static
 * hosts on this fixed response subset and fails loudly on anything else.
 */
class Connection implements AutoCloseable {

	// 8 in flight per connection: 10 KB files @ 300 ms RTT @ 5 Mbps up → BDP ≈ 187 KB ≈ 19 outstanding files; 5 connections × 8 = 40 ≈ 2× BDP, so the pipe stays full while a connection re-handshakes. Bigger/faster than
	// the envelope is bandwidth-bound and K stops mattering.
	static final int PIPELINE_DEPTH = 8;

	private static final byte[] CRLF = {'\r', '\n'};
	private static final String ACCEPT_ENCODING = "Accept-Encoding: " + WireCodec.offeredEncodings() + "\r\n";
	// Our own server never redirects; the cap exists for foreign static hosts, so only a misconfigured redirect loop touches it.
	private static final int MAX_REDIRECTS = 3;
	// Response header lines are tiny; a line past this or a block of this many lines is a hostile or broken peer.
	private static final int MAX_HEADER_LINES = 128;
	/** The document verdict for one conditional response; the body hash decides, never the status alone. */
	private record ResponseHead(int status, Long contentLength, String contentRange, String contentEncoding, String location, boolean connectionClose, boolean chunked) {}

	private final SSLSocket socket;
	private final Socket transport;
	private final Executor executor;
	private final BufferedInputStream in;
	private final BufferedOutputStream out;
	// The request head around the path, encoded once per connection: the request line, Host, User-Agent, and the Bearer secret when held.
	private final byte[] requestLinePrefix;
	private final byte[] requestLineSuffix;
	private final Runnable onSlotFreed;
	// Guards the pending queue together with the request-head writes, so queue order always matches socket order. It is
	// never held across a response read, and the pipelined bytes it can have unwritten are depth-capped around 10 KB -
	// well inside kernel send buffers - so even a write taken while the pool lock is held cannot block it.
	private final Object gate = new Object();
	private final ArrayDeque<Pending<?>> pending = new ArrayDeque<>();
	private boolean readerRunning;
	private volatile boolean unhealthy;
	private final String traceId = Integer.toHexString(System.identityHashCode(this));

	String traceId() {
		return traceId;
	}

	private static String shortPath(String path) {
		int slash = path.lastIndexOf('/');
		return slash >= 0 && path.length() - slash > 24 ? path.substring(path.length() - 24) : path;
	}

	Connection(SSLSocket socket, Socket transport, String secret, String hostHeader, Executor executor, Runnable onSlotFreed) throws IOException {
		if (socket == null || socket.isClosed()) throw new IOException("Server connection is closed");
		if (transport != null && transport.isClosed()) throw new IOException("Server connection is closed");
		this.socket = socket;
		this.transport = transport;
		this.executor = executor;
		this.in = new BufferedInputStream(socket.getInputStream());
		this.out = new BufferedOutputStream(socket.getOutputStream());
		this.onSlotFreed = onSlotFreed;
		this.requestLinePrefix = "GET ".getBytes(StandardCharsets.UTF_8);
		String authorization = secret == null ? "" : "Authorization: Bearer " + secret + "\r\n";
		this.requestLineSuffix = (" HTTP/1.1\r\nHost: " + hostHeader + "\r\nUser-Agent: " + USER_AGENT + "\r\n" + authorization).getBytes(StandardCharsets.UTF_8);
	}

	boolean isActive() {
		return !unhealthy && !socket.isClosed() && (transport == null || !transport.isClosed());
	}

	/** One free pipeline slot; the pool lock serializes who acts on it. */
	boolean hasFreeSlot() {
		synchronized (gate) {
			return !unhealthy && pending.size() < PIPELINE_DEPTH;
		}
	}

	/**
	 * Object request by sha1; the range is [{@code offset}, {@code endInclusive}] ({@code endInclusive < 0} means EOF) and is answered append-only behind a validated start.
	 * {@code offerEncoding} is plain negotiation: true sends Accept-Encoding and may receive an encoded chunked body,
	 * false asks for identity and an ordinary Content-Length. {@code limitBytes} rejects an object whose declared
	 * length exceeds it before a body byte is read (negative means no limit).
	 */
	public CompletableFuture<Path> sendDownloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback, long offset, long endInclusive, OutputStream tap, boolean offerEncoding, long limitBytes) {
		return submit(new ObjectRequest("/objects/" + new String(fileHash, StandardCharsets.UTF_8), destination, offset, endInclusive, chunkCallback, tap, offerEncoding, limitBytes));
	}

	/** Document request (reserved keys); a non-null expected hash may be answered 304, and the 200 body hash is the ground truth. */
	public CompletableFuture<DocumentFetch> sendDownloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer chunkCallback) {
		return submit(new DocumentRequest("/" + new String(key, StandardCharsets.UTF_8), destination, expectedSha1Hex, chunkCallback, null));
	}

	/** The same request with a tap: served body bytes reach the tap (decode-while-downloading) and the destination alike. */
	public CompletableFuture<DocumentFetch> sendDownloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer chunkCallback, OutputStream tap) {
		return submit(new DocumentRequest("/" + new String(key, StandardCharsets.UTF_8), destination, expectedSha1Hex, chunkCallback, tap));
	}

	/** Registers one request and writes its head; the pool only hands out free slots, so an over-submit is a tripwire, not a flow-control path. */
	private <T> CompletableFuture<T> submit(Pending<T> request) {
		synchronized (gate) {
			if (unhealthy) {
				WireTrace.log("SUBMIT_REJECT", "conn", traceId, "task", shortPath(request.originPath), "why", "unhealthy");
				request.future.completeExceptionally(new IOException("Server connection is closed"));
				return request.future;
			}
			if (pending.size() >= PIPELINE_DEPTH) {
				WireTrace.log("SUBMIT_REJECT", "conn", traceId, "task", shortPath(request.originPath), "why", "depth");
				request.future.completeExceptionally(new IOException("Connection pipeline exceeded its depth of " + PIPELINE_DEPTH));
				return request.future;
			}
			try {
				writeRequest(request.path, request.headers());
			} catch (IOException e) {
				WireTrace.log("SUBMIT_REJECT", "conn", traceId, "task", shortPath(request.originPath), "why", "write:" + e);
				request.future.completeExceptionally(e);
				failPending(e);
				return request.future;
			}
			pending.addLast(request);
			if (!readerRunning) {
				readerRunning = true;
				try {
					executor.execute(this::readLoop);
				} catch (RuntimeException rejected) {
					pending.removeLastOccurrence(request);
					request.future.completeExceptionally(rejected);
					failPending(rejected);
				}
			}
		}
		return request.future;
	}

	private void writeRequest(String path, String extraHeaders) throws IOException {
		out.write(requestLinePrefix);
		out.write(path.getBytes(StandardCharsets.UTF_8));
		out.write(requestLineSuffix);
		if (extraHeaders != null) out.write(extraHeaders.getBytes(StandardCharsets.UTF_8));
		out.write(CRLF);
		out.flush();
	}

	private void readLoop() {
		while (true) {
			Pending<?> request;
			synchronized (gate) {
				// The head stays queued while its response is read: the slot is taken until the response settles, so the depth never undercounts.
				request = pending.peekFirst();
				if (request == null) {
					readerRunning = false;
					return;
				}
			}
			boolean settled;
			try {
				settled = respond(request);
			} catch (Throwable failure) {
				int waiting;
				synchronized (gate) {
					waiting = pending.size();
				}
				LOGGER.warn("The modpack wire lane died: conn={} pending={} request={} failure={}", traceId, waiting, request.originPath, failure.toString());
				WireTrace.log("READER_EXIT", "conn", traceId, "reason", "fail:" + failure);
				request.future.completeExceptionally(failure);
				failPending(failure);
				return;
			}
			if (settled) {
				synchronized (gate) {
					pending.pollFirst();
				}
				try {
					onSlotFreed.run();
				} catch (Throwable failure) {
					// The slot is freed at the connection, but a dead pool callback would strand the rest of this
					// pipeline silently - the reader would die with slots still counted. Fail the lane loudly instead.
					WireTrace.log("READER_EXIT", "conn", traceId, "reason", "slot-free-callback:" + failure);
					failPending(new IOException("Slot-free callback failed", failure));
					return;
				}
			}
		}
	}

	/** Handles one response for the queue's head request; false means a redirect was re-issued and the slot stays taken. */
	private boolean respond(Pending<?> request) throws IOException {
		ResponseHead head = parseResponseHead();
		if (isRedirect(head)) {
			discardBody(head);
			if (request.redirects >= MAX_REDIRECTS) throw new IOException("More than " + MAX_REDIRECTS + " redirects for " + request.originPath);
			request.redirects++;
			reissue(request, redirectTarget(request.path, head));
			return false;
		}
		request.deliver(head);
		return true;
	}

	/**
	 * A redirect re-issue is appended at the back: its bytes are written after every request submitted in the meantime,
	 * so the responses keep arriving in queue order and the reader stays aligned by construction.
	 */
	private void reissue(Pending<?> request, String target) throws IOException {
		synchronized (gate) {
			pending.pollFirst();
			request.path = target;
			writeRequest(target, request.headers());
			pending.addLast(request);
		}
	}

	/** Alignment is lost past a failed response: every pending request fails, and the download manager retries the idempotent GETs. */
	private void failPending(Throwable cause) {
		unhealthy = true;
		List<Pending<?>> failed;
		synchronized (gate) {
			failed = new ArrayList<>(pending);
			pending.clear();
		}
		WireTrace.log("FAIL_PENDING", "conn", traceId, "count", failed.size(), "cause", String.valueOf(cause));
		closeSocket();
		for (Pending<?> request : failed) request.future.completeExceptionally(cause);
		onSlotFreed.run();
	}
	private abstract class Pending<T> {
		final CompletableFuture<T> future = new CompletableFuture<>();
		final String originPath;
		final Path destination;
		final IntConsumer chunks;
		String path;
		int redirects;

		Pending(String path, Path destination, IntConsumer chunks) {
			this.path = path;
			this.originPath = path;
			this.destination = destination;
			this.chunks = chunks;
		}

		abstract String headers();

		abstract void deliver(ResponseHead head) throws IOException;
	}

	private final class ObjectRequest extends Pending<Path> {
		private final long offset;
		private final long endInclusive;
		private final OutputStream tap;
		private final boolean offerEncoding;
		private final long limitBytes;

		ObjectRequest(String path, Path destination, long offset, long endInclusive, IntConsumer chunks, OutputStream tap, boolean offerEncoding, long limitBytes) {
			super(path, destination, chunks);
			this.offset = offset;
			this.endInclusive = endInclusive;
			this.tap = tap;
			this.offerEncoding = offerEncoding;
			this.limitBytes = limitBytes;
		}

		@Override
		String headers() {
			String end = endInclusive >= 0 ? "-" + endInclusive : "-";
			String range = endInclusive >= 0 || offset > 0 ? "Range: bytes=" + offset + end + "\r\n" : "";
			return range + (offerEncoding ? ACCEPT_ENCODING : "");
		}

		/** True when the guardrail applies and the response declares more bytes than it allows; no limit or no declared length never trips. */
		private boolean overLimit(ResponseHead head) {
			return limitBytes >= 0 && head.contentLength() != null && head.contentLength() > limitBytes;
		}

		@Override
		void deliver(ResponseHead head) throws IOException {
			if (overLimit(head)) {
				// The declared length busts the guardrail: the body is discarded so the lane stays aligned, and only this request fails.
				discardBody(head);
				future.completeExceptionally(new IOException("Object exceeds the " + limitBytes + " byte limit for " + originPath));
				return;
			}
			if (head.status() == 206) {
				// A 206 may only be appended behind the stored prefix when the server actually resumed at the requested offset; anything else fails fast instead of splicing together bytes that promotion would only
				// reject after the fact. An encoded body is framed chunked and has no length by design.
				if (head.contentLength() == null && !head.chunked()) throw new IOException("HTTP 206 without Content-Length");
				requireResumeStart(head, offset);
				if (consumeBody(head, destination, offset, chunks, null, tap, false, limitBytes)) {
					future.completeExceptionally(new IOException("Object exceeds the " + limitBytes + " byte limit for " + originPath));
					return;
				}
				future.complete(destination);
				return;
			}
			if (head.status() == 200) {
				// A server that ignores Range answers 200 with the full body and no Content-Range; the fresh attempt's empty partial makes the restart from zero correct for an open-ended request then, and a bounded one
				// cannot be satisfied at all.
				if (endInclusive >= 0) throw new IOException("Server ignored the Range end for " + originPath);
				boolean resumed = offset > 0 && head.contentRange() != null;
				if (resumed) requireResumeStart(head, offset);
				if (consumeBody(head, destination, resumed ? offset : 0, chunks, null, tap, false, limitBytes)) {
					future.completeExceptionally(new IOException("Object exceeds the " + limitBytes + " byte limit for " + originPath));
					return;
				}
				future.complete(destination);
				return;
			}
			discardBody(head);
			// A failed response is thrown, not completed quietly: the reader treats any failure on the connection as lost alignment and fails every pending request with it.
			throw statusFailure(head, offset > 0 || endInclusive >= 0);
		}
	}

	private final class DocumentRequest extends Pending<DocumentFetch> {
		private final String expectedSha1Hex;
		private final OutputStream tap;

		DocumentRequest(String path, Path destination, String expectedSha1Hex, IntConsumer chunks, OutputStream tap) {
			super(path, destination, chunks);
			this.expectedSha1Hex = expectedSha1Hex;
			this.tap = tap;
		}

		@Override
		String headers() {
			String conditional = expectedSha1Hex == null ? "" : "If-None-Match: \"" + expectedSha1Hex + "\"\r\n";
			return conditional + ACCEPT_ENCODING;
		}

		@Override
		void deliver(ResponseHead head) throws IOException {
			if (head.status() == 304) {
				if (expectedSha1Hex == null) throw new IOException("HTTP 304 without a sent If-None-Match");
				future.complete(new DocumentFetch(null, true));
				return;
			}
			if (head.status() == 200) {
				if (expectedSha1Hex == null) {
					consumeBody(head, destination, 0, chunks, null, null, true, -1L);
					future.complete(new DocumentFetch(destination, false));
					return;
				}
				// A conditional document's body hash is the ground truth, so a host that ignores the condition still reads as unchanged when the bytes match the expectation.
				MessageDigest hash = HashUtils.newSha1Digest();
				consumeBody(head, destination, 0, chunks, hash, null, true, -1L);
				future.complete(new DocumentFetch(destination, HexFormat.of().formatHex(hash.digest()).equals(expectedSha1Hex)));
				return;
			}
			// Same rule as the object path: a framed error status fails this request only.
			discardBody(head);
			future.completeExceptionally(statusFailure(head, false));
		}
	}

	private static boolean isRedirect(ResponseHead head) {
		// 304 is a 3xx that is never a redirect; it carries no Location and is answered as its own verdict.
		return head.status() >= 300 && head.status() < 400 && head.status() != 304;
	}

	/** The re-issued GET path: the Location resolved against the current request path, authority dropped - the connection is pinned to one TLS peer. */
	private static String redirectTarget(String requestPath, ResponseHead head) throws IOException {
		if (head.location() == null || head.location().isBlank()) throw new IOException("Redirect without a Location header");
		URI resolved = URI.create("https://automodpack.invalid" + requestPath).resolve(URI.create(head.location()));
		String target = resolved.getPath();
		if (target == null || target.isEmpty()) throw new IOException("Redirect Location without a path: " + head.location());
		return target;
	}

	private IOException statusFailure(ResponseHead head, boolean ranged) {
		return switch (head.status()) {
			case 401 -> new UnauthorizedException();
			case 404, 410 -> new MissingObjectException();
			case 416 -> ranged ? new StaleRangeException() : new IOException("HTTP 416 without a sent Range");
			default -> new IOException("HTTP " + head.status());
		};
	}

	private void requireResumeStart(ResponseHead head, long offset) throws IOException {
		String contentRange = head.contentRange();
		if (contentRange == null) throw new StaleRangeException();
		String spec = contentRange.trim();
		if (!spec.startsWith("bytes ")) throw new IOException("Unparseable Content-Range: " + contentRange);
		int dash = spec.indexOf('-');
		if (dash < 0) throw new IOException("Unparseable Content-Range: " + contentRange);
		long start;
		try {
			start = Long.parseLong(spec.substring("bytes ".length(), dash).trim());
		} catch (NumberFormatException e) {
			throw new IOException("Unparseable Content-Range: " + contentRange);
		}
		if (start != offset) throw new StaleRangeException();
	}

	private ResponseHead parseResponseHead() throws IOException {
		HttpHead head = HttpHead.read(in);
		String contentLengthValue = head.headerValue("content-length");
		Long contentLength = contentLengthValue == null ? null : parseContentLength(contentLengthValue);
		String connection = head.headerValue("connection");
		return new ResponseHead(head.status(), contentLength, head.headerValue("content-range"), head.headerValue("content-encoding"), head.headerValue("location"),
				connection != null && connection.toLowerCase(Locale.ROOT).contains("close"), head.headerValue("transfer-encoding") != null);
	}

	private static long parseContentLength(String value) throws IOException {
		try {
			long length = Long.parseLong(value);
			if (length < 0) throw new NumberFormatException();
			return length;
		} catch (NumberFormatException e) {
			throw new IOException("Unparseable Content-Length: " + value);
		}
	}

	/**
	 * Writes the body per the framing rules; a bodyless status consumes nothing. Called with a null destination to
	 * discard an error body. The framed source - Content-Length, chunked, or close - ends exactly where the next
	 * pipelined response head begins, so alignment is the frame's business, never the codec's: a decoder is free to
	 * stop at its own stream end (gzip peeks the wire via available(), which loses to a terminator still in flight),
	 * and the frame drain below consumes whatever framing bytes the decoder left behind. Truncate is only honest for a
	 * single-writer destination (documents): an object partial shares positioned writers across pipelined requests, so
	 * a truncate there would wipe bytes another writer already settled. A non-negative limit stops the copy once the
	 * decoded body passes it and still drains the frame, so the return value says whether the limit was the verdict.
	 */
	private boolean consumeBody(ResponseHead head, Path destination, long writeOffset, IntConsumer chunkCallback, MessageDigest hash, OutputStream tap, boolean truncate, long limitBytes) throws IOException {
		if (head.status() == 204 || head.status() == 304) return false;
		InputStream framed;
		boolean closeFramed = false;
		if (head.chunked()) {
			framed = new ChunkedBody();
		} else if (head.contentLength() != null) {
			framed = new BoundedBody(head.contentLength());
		} else {
			if (head.status() != 200) return false;
			framed = in;
			closeFramed = true;
		}
		String encoding = head.contentEncoding() == null ? "" : head.contentEncoding().trim().toLowerCase(Locale.ROOT);
		WireCodec codec = WireCodec.negotiate(encoding);
		if (codec == null && !encoding.isEmpty()) throw new IOException("Unsupported Content-Encoding: " + head.contentEncoding());
		InputStream source = codec == null ? framed : codec.unwrap(framed);
		LimitedBody limited = limitBytes >= 0 ? new LimitedBody(source, limitBytes) : null;
		if (limited != null) source = limited;
		if (closeFramed) unhealthy = true;
		transfer(source, destination, writeOffset, chunkCallback, hash, head.contentLength(), tap, truncate);
		if (framed instanceof ChunkedBody chunked) chunked.drainToFrameEnd();
		if (framed instanceof BoundedBody bounded && bounded.remaining() > 0) throw new IOException("Response body ended before the promised Content-Length");
		if (head.connectionClose()) unhealthy = true;
		return limited != null && limited.exceeded();
	}

	/**
	 * The body reads to the end of its framed source - exactly Content-Length bytes through the bounded wrapper, or EOF
	 * on a close-framed body - so a zstd body is decoded on the way in and the next pipelined response head still parses
	 * behind its exact byte count. Every write lands at its absolute file position: ranged bodies from several lanes can
	 * share one partial without coordinating, and promotion judges the assembled whole.
	 */
	private void transfer(InputStream source, Path destination, long writeOffset, IntConsumer chunkCallback, MessageDigest hash, Long compressedLength, OutputStream tap, boolean truncate) throws IOException {
		byte[] buffer = new byte[(int) Math.min(DEFAULT_CHUNK_SIZE, compressedLength == null ? DEFAULT_CHUNK_SIZE : compressedLength)];
		try (OutputStream fos = destination == null ? null : truncate && writeOffset == 0 ? LocalFileWriter.open(destination) : LocalFileWriter.openAt(destination, writeOffset)) {
			int read;
			while ((read = source.read(buffer, 0, buffer.length)) >= 0) {
				if (fos != null) fos.write(buffer, 0, read);
				if (tap != null) tap.write(buffer, 0, read);
				if (hash != null) hash.update(buffer, 0, read);
				if (chunkCallback != null) chunkCallback.accept(read);
			}
		}
	}

	private void discardBody(ResponseHead head) throws IOException {
		consumeBody(head, null, 0, null, null, null, false, -1L);
	}

	/**
	 * Serves at most {@code limit} decoded bytes, then reports EOF once and remembers whether the body actually
	 * carried more: an honest body of exactly the limit reads as complete, a longer one reads as over the cap while
	 * the frame drain walks the remaining body bytes off the wire.
	 */
	private static final class LimitedBody extends InputStream {
		private final InputStream source;
		private long remaining;
		private boolean exceeded;

		LimitedBody(InputStream source, long limit) {
			this.source = source;
			this.remaining = limit;
		}

		boolean exceeded() {
			return exceeded;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int read = read(one, 0, 1);
			return read < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (remaining <= 0) {
				exceeded = source.read() >= 0;
				return -1;
			}
			int read = source.read(buffer, offset, (int) Math.min(length, remaining));
			if (read > 0) remaining -= read;
			return read;
		}
	}

	/** Reads at most {@code total} bytes from the socket, so a decoded body can never consume the next pipelined response's bytes. */
	private final class BoundedBody extends InputStream {
		private long remaining;

		BoundedBody(long total) {
			remaining = total;
		}

		long remaining() {
			return remaining;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int read = read(one, 0, 1);
			return read < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (remaining <= 0) return -1;
			int read = in.read(buffer, offset, (int) Math.min(length, remaining));
			if (read > 0) remaining -= read;
			return read;
		}
	}

	/**
	 * De-frames a chunked body straight off the socket: each chunk is a hex size line, its bytes, and a CRLF; a zero
	 * size ends the body behind its trailer block. The framing is exact, so the next pipelined response head still
	 * parses behind it; a stretched line or a truncated chunk is a hostile or broken peer and fails the connection.
	 */
	private final class ChunkedBody extends InputStream {
		private long chunkRemaining;
		private boolean done;

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int read = read(one, 0, 1);
			return read < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (done) return -1;
			if (chunkRemaining == 0) {
				long size = parseChunkSize(HttpHead.readLine(in));
				if (size == 0) {
					consumeTrailers();
					done = true;
					return -1;
				}
				chunkRemaining = size;
			}
			int read = in.read(buffer, offset, (int) Math.min(length, chunkRemaining));
			if (read < 0) throw new IOException("Connection ended inside a chunked body");
			chunkRemaining -= read;
			if (chunkRemaining == 0) expectCrlf();
			return read;
		}

		/**
		 * Consumes the rest of the body's frame after the decoder has hit EOF: chunk bytes it never asked for, the
		 * terminator, and the trailers. The terminator arriving after the last coding byte must still land here, so the
		 * next response head starts exactly behind this body.
		 */
		void drainToFrameEnd() throws IOException {
			while (!done) {
				if (chunkRemaining == 0) {
					long size = parseChunkSize(HttpHead.readLine(in));
					if (size == 0) {
						consumeTrailers();
						done = true;
						return;
					}
					chunkRemaining = size;
				}
				long skipped = in.skip(chunkRemaining);
				if (skipped <= 0) {
					if (in.read() < 0) throw new IOException("Connection ended inside a chunked body");
					skipped = 1;
				}
				chunkRemaining -= skipped;
				if (chunkRemaining == 0) expectCrlf();
			}
		}

		private long parseChunkSize(String line) throws IOException {
			String hex = line.indexOf(';') >= 0 ? line.substring(0, line.indexOf(';')).trim() : line.trim();
			try {
				long size = Long.parseLong(hex, 16);
				if (size < 0) throw new NumberFormatException();
				return size;
			} catch (NumberFormatException | StringIndexOutOfBoundsException e) {
				throw new IOException("Unparseable chunk size: " + line);
			}
		}

		private void expectCrlf() throws IOException {
			if (in.read() != '\r' || in.read() != '\n') throw new IOException("Chunked body is missing a chunk terminator");
		}

		/** The size line's CRLF is already consumed, so the first trailer line (empty when there are none) reads next. */
		private void consumeTrailers() throws IOException {
			int lines = 0;
			while (!HttpHead.readLine(in).isEmpty()) {
				if (++lines > MAX_HEADER_LINES) throw new IOException("Chunked trailer block exceeded " + MAX_HEADER_LINES + " lines");
			}
		}
	}

	@Override
	public void close() {
		failPending(new IOException("Server connection is closed"));
	}

	private void closeSocket() {
		try {
			socket.close();
		} catch (Exception ignored) {
		}
	}
}
