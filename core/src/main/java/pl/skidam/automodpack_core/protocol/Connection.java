package pl.skidam.automodpack_core.protocol;

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

import io.airlift.compress.zstd.ZstdInputStream;

import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * One pooled TLS connection speaking minimal HTTP/1.1 against the modpack contract: {@code GET /<document>} and
 * {@code GET /objects/<sha1>}. Up to {@link #PIPELINE_DEPTH} requests sit in flight, written as the pool hands out
 * slots, and one reader per connection consumes the responses strictly in order; a failure anywhere fails every
 * pending request, because alignment is lost and every request is an idempotent GET whose retry belongs to the
 * download manager. The parser survives foreign static hosts on a tiny fixed response subset - status line,
 * Content-Length, Content-Range, Content-Encoding, Connection, Location - and fails loudly on anything else (chunked
 * included): our server never sends it and hand-rolled chunk decoding is not worth the risk.
 */
class Connection implements AutoCloseable {

	// 8 in flight per connection: 10 KB files @ 300 ms RTT @ 5 Mbps up → BDP ≈ 187 KB ≈ 19 outstanding files; 5 connections × 8 = 40 ≈ 2× BDP, so the pipe stays full while a connection re-handshakes. Bigger/faster than
	// the envelope is bandwidth-bound and K stops mattering.
	static final int PIPELINE_DEPTH = 8;

	private static final byte[] CRLF = {'\r', '\n'};
	private static final String ACCEPT_ENCODING = "Accept-Encoding: zstd\r\n";
	private static final int MAX_REDIRECTS = 3;
	// Response header lines are tiny; a line past this or a block of this many lines is a hostile or broken peer.
	private static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
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

	/** Object request by sha1; a positive offset resumes from there and is answered append-only behind a validated start. */
	public CompletableFuture<Path> sendDownloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback, long offset) {
		return submit(new ObjectRequest("/objects/" + new String(fileHash, StandardCharsets.UTF_8), destination, offset, chunkCallback));
	}

	/** Document request (reserved keys); a non-null expected hash may be answered 304, and the 200 body hash is the ground truth. */
	public CompletableFuture<DocumentFetch> sendDownloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer chunkCallback) {
		return submit(new DocumentRequest("/" + new String(key, StandardCharsets.UTF_8), destination, expectedSha1Hex, chunkCallback));
	}

	/** Registers one request and writes its head; the pool only hands out free slots, so an over-submit is a tripwire, not a flow-control path. */
	private <T> CompletableFuture<T> submit(Pending<T> request) {
		synchronized (gate) {
			if (unhealthy) {
				request.future.completeExceptionally(new IOException("Server connection is closed"));
				return request.future;
			}
			if (pending.size() >= PIPELINE_DEPTH) {
				request.future.completeExceptionally(new IOException("Connection pipeline exceeded its depth of " + PIPELINE_DEPTH));
				return request.future;
			}
			try {
				writeRequest(request.path, request.headers());
			} catch (IOException e) {
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
				request.future.completeExceptionally(failure);
				failPending(failure);
				return;
			}
			if (settled) {
				synchronized (gate) {
					pending.pollFirst();
				}
				onSlotFreed.run();
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

		ObjectRequest(String path, Path destination, long offset, IntConsumer chunks) {
			super(path, destination, chunks);
			this.offset = offset;
		}

		@Override
		String headers() {
			return offset > 0 ? "Range: bytes=" + offset + "-\r\n" : null;
		}

		@Override
		void deliver(ResponseHead head) throws IOException {
			if (head.status() == 206) {
				// A 206 may only be appended behind the stored prefix when the server actually resumed at the requested offset; anything else fails fast instead of splicing together bytes that promotion would only
				// reject after the fact.
				if (head.contentLength() == null) throw new IOException("HTTP 206 without Content-Length");
				requireResumeStart(head, offset);
				consumeBody(head, destination, true, chunks, null);
				future.complete(destination);
				return;
			}
			if (head.status() == 200) {
				// A server that ignores Range answers 200 with the full body and no Content-Range; the truncate is the correct result then.
				boolean append = offset > 0 && head.contentRange() != null;
				if (append) requireResumeStart(head, offset);
				consumeBody(head, destination, append, chunks, null);
				future.complete(destination);
				return;
			}
			discardBody(head);
			// A failed response is thrown, not completed quietly: the reader treats any failure on the connection as lost alignment and fails every pending request with it.
			throw statusFailure(head, offset > 0);
		}
	}

	private final class DocumentRequest extends Pending<DocumentFetch> {
		private final String expectedSha1Hex;

		DocumentRequest(String path, Path destination, String expectedSha1Hex, IntConsumer chunks) {
			super(path, destination, chunks);
			this.expectedSha1Hex = expectedSha1Hex;
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
					consumeBody(head, destination, false, chunks, null);
					future.complete(new DocumentFetch(destination, false));
					return;
				}
				// A conditional document's body hash is the ground truth, so a host that ignores the condition still reads as unchanged when the bytes match the expectation.
				MessageDigest hash = HashUtils.newSha1Digest();
				consumeBody(head, destination, false, chunks, hash);
				future.complete(new DocumentFetch(destination, HexFormat.of().formatHex(hash.digest()).equals(expectedSha1Hex)));
				return;
			}
			discardBody(head);
			// Same rule as the object path: a failed response throws, and the pending set behind it fails with it.
			throw statusFailure(head, false);
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
			case 404, 410 -> new IOException("HTTP " + head.status());
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
		String statusLine = readLine();
		if (!statusLine.startsWith("HTTP/1.1 ")) throw new IOException("Not an HTTP/1.1 response: " + statusLine);
		String[] parts = statusLine.split(" ", 3);
		int status;
		try {
			status = Integer.parseInt(parts[1]);
		} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
			throw new IOException("Unparseable HTTP status line: " + statusLine);
		}
		Long contentLength = null;
		String contentRange = null;
		String contentEncoding = null;
		String location = null;
		boolean connectionClose = false;
		boolean chunked = false;
		int lines = 0;
		while (lines++ < MAX_HEADER_LINES) {
			String header = readLine();
			if (header.isEmpty()) return new ResponseHead(status, contentLength, contentRange, contentEncoding, location, connectionClose, chunked);
			int colon = header.indexOf(':');
			if (colon <= 0) continue;
			String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = header.substring(colon + 1).trim();
			switch (name) {
				case "content-length" -> contentLength = parseContentLength(value, header);
				case "content-range" -> contentRange = value;
				case "content-encoding" -> contentEncoding = value;
				case "location" -> location = value;
				case "connection" -> connectionClose = value.toLowerCase(Locale.ROOT).contains("close");
				case "transfer-encoding" -> chunked = true;
				default -> {
				}
			}
		}
		throw new IOException("Response header block exceeded " + MAX_HEADER_LINES + " lines");
	}

	private static long parseContentLength(String value, String header) throws IOException {
		try {
			long length = Long.parseLong(value);
			if (length < 0) throw new NumberFormatException();
			return length;
		} catch (NumberFormatException e) {
			throw new IOException("Unparseable Content-Length: " + header);
		}
	}

	/** Reads one CRLF-terminated header line; TLS already framed the records, so only a hostile peer can stretch a line. */
	private String readLine() throws IOException {
		StringBuilder line = new StringBuilder(64);
		int previous = -1;
		while (true) {
			int read = in.read();
			if (read < 0) throw new IOException("Connection ended inside a response header");
			if (previous == '\r' && read == '\n') return line.substring(0, line.length() - 1);
			line.append((char) read);
			if (line.length() > MAX_HEADER_LINE_BYTES) throw new IOException("Response header line exceeded " + MAX_HEADER_LINE_BYTES + " bytes");
			previous = read;
		}
	}

	/** Writes the body per the framing rules; a bodyless status consumes nothing. Called with a null destination to discard an error body. */
	private void consumeBody(ResponseHead head, Path destination, boolean append, IntConsumer chunkCallback, MessageDigest hash) throws IOException {
		if (head.chunked()) throw new IOException("Chunked responses are not supported");
		if (head.status() == 204 || head.status() == 304) return;
		if (head.contentLength() == null && head.status() != 200) return;
		boolean zstd = head.contentEncoding() != null && head.contentEncoding().trim().equalsIgnoreCase("zstd");
		BoundedBody bounded = head.contentLength() == null ? null : new BoundedBody(head.contentLength());
		InputStream source = bounded == null ? in : bounded;
		if (zstd) source = new ZstdInputStream(source);
		if (bounded == null) unhealthy = true;
		transfer(source, destination, append, chunkCallback, hash, head.contentLength());
		if (bounded != null && bounded.remaining() > 0) throw new IOException("Response body ended before the promised Content-Length");
		if (head.connectionClose()) unhealthy = true;
	}

	/**
	 * The body reads to the end of its framed source - exactly Content-Length bytes through the bounded wrapper, or EOF
	 * on a close-framed body - so a zstd body is decoded on the way in and the next pipelined response head still parses
	 * behind its exact byte count.
	 */
	private void transfer(InputStream source, Path destination, boolean append, IntConsumer chunkCallback, MessageDigest hash, Long compressedLength) throws IOException {
		byte[] buffer = new byte[(int) Math.min(DEFAULT_CHUNK_SIZE, compressedLength == null ? DEFAULT_CHUNK_SIZE : compressedLength)];
		try (OutputStream fos = destination == null ? null : append ? LocalFileWriter.openAppending(destination) : LocalFileWriter.open(destination)) {
			int read;
			while ((read = source.read(buffer, 0, buffer.length)) >= 0) {
				if (fos != null) fos.write(buffer, 0, read);
				if (hash != null) hash.update(buffer, 0, read);
				if (chunkCallback != null) chunkCallback.accept(read);
			}
		}
	}

	private void discardBody(ResponseHead head) throws IOException {
		consumeBody(head, null, false, null, null);
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
