package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.protocol.NetUtils.DEFAULT_CHUNK_SIZE;
import static pl.skidam.automodpack_core.protocol.NetUtils.USER_AGENT;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

import javax.net.ssl.SSLSocket;

import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * One pooled TLS connection speaking minimal HTTP/1.1 against the modpack contract: {@code GET /<document>} and
 * {@code GET /objects/<sha1>}. The parser survives foreign static hosts on a tiny fixed response subset - status
 * line, Content-Length, Content-Range, Content-Encoding, ETag, Connection, Location - and fails loudly on anything
 * else (chunked included): our server never sends it and hand-rolled chunk decoding is not worth the risk.
 */
class Connection implements AutoCloseable {

	private static final byte[] CRLF = {'\r', '\n'};
	private static final int MAX_REDIRECTS = 3;
	// Response header lines are tiny; a line past this or a block of this many lines is a hostile or broken peer.
	private static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
	private static final int MAX_HEADER_LINES = 128;
	/** The document verdict for one conditional response; the body hash decides, never the status alone. */
	private record ResponseHead(int status, Long contentLength, String contentRange, String contentEncoding, String etag, String location, boolean connectionClose, boolean chunked) {}

	private final SSLSocket socket;
	private final Socket transport;
	private final Executor executor;
	private final BufferedInputStream in;
	private final BufferedOutputStream out;
	// The request head around the path, encoded once per connection: the request line, Host, User-Agent, and the Bearer secret when held.
	private final byte[] requestLinePrefix;
	private final byte[] requestLineSuffix;
	private volatile boolean unhealthy;

	Connection(SSLSocket socket, Socket transport, String secret, String hostHeader, Executor executor) throws IOException {
		if (socket == null || socket.isClosed()) throw new IOException("Server connection is closed");
		if (transport != null && transport.isClosed()) throw new IOException("Server connection is closed");
		this.socket = socket;
		this.transport = transport;
		this.executor = executor;
		this.in = new BufferedInputStream(socket.getInputStream());
		this.out = new BufferedOutputStream(socket.getOutputStream());
		this.requestLinePrefix = "GET ".getBytes(StandardCharsets.UTF_8);
		String authorization = secret == null ? "" : "Authorization: Bearer " + secret + "\r\n";
		this.requestLineSuffix = (" HTTP/1.1\r\nHost: " + hostHeader + "\r\nUser-Agent: " + USER_AGENT + "\r\n" + authorization).getBytes(StandardCharsets.UTF_8);
	}

	boolean isActive() {
		return !unhealthy && !socket.isClosed() && (transport == null || !transport.isClosed());
	}

	/** Object request by sha1; a positive offset resumes from there and is answered append-only behind a validated start. */
	public CompletableFuture<Path> sendDownloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback, long offset) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return fetchObject("/objects/" + new String(fileHash, StandardCharsets.UTF_8), destination, offset, chunkCallback);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, executor);
	}

	/** Document request (reserved keys); a non-null expected hash may be answered 304, and the 200 body hash is the ground truth. */
	public CompletableFuture<DocumentFetch> sendDownloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer chunkCallback) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return fetchDocument("/" + new String(key, StandardCharsets.UTF_8), destination, expectedSha1Hex, chunkCallback);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, executor);
	}

	private Path fetchObject(String path, Path destination, long offset, IntConsumer chunkCallback) throws IOException {
		boolean ranged = offset > 0;
		String range = ranged ? "Range: bytes=" + offset + "-\r\n" : null;
		String current = path;
		int redirects = 0;
		while (true) {
			ResponseHead head = request(current, range);
			if (isRedirect(head)) {
				discardBody(head);
				if (++redirects > MAX_REDIRECTS) throw new IOException("More than " + MAX_REDIRECTS + " redirects for " + path);
				current = redirectTarget(current, head);
				continue;
			}
			if (head.status() == 206) {
				// A 206 may only be appended behind the stored prefix when the server actually resumed at the requested offset; anything else fails fast instead of splicing together bytes that promotion would only
				// reject after the fact.
				if (head.contentLength() == null) throw new IOException("HTTP 206 without Content-Length");
				requireResumeStart(head, offset);
				consumeBody(head, destination, true, chunkCallback, null);
				return destination;
			}
			if (head.status() == 200) {
				// A server that ignores Range answers 200 with the full body and no Content-Range; the truncate is the correct result then.
				boolean append = ranged && head.contentRange() != null;
				if (append) requireResumeStart(head, offset);
				consumeBody(head, destination, append, chunkCallback, null);
				return destination;
			}
			discardBody(head);
			throw statusFailure(head, ranged);
		}
	}

	private DocumentFetch fetchDocument(String path, Path destination, String expectedSha1Hex, IntConsumer chunkCallback) throws IOException {
		String conditional = expectedSha1Hex == null ? null : "If-None-Match: \"" + expectedSha1Hex + "\"\r\n";
		String current = path;
		int redirects = 0;
		while (true) {
			ResponseHead head = request(current, conditional);
			if (isRedirect(head)) {
				discardBody(head);
				if (++redirects > MAX_REDIRECTS) throw new IOException("More than " + MAX_REDIRECTS + " redirects for " + path);
				current = redirectTarget(current, head);
				continue;
			}
			if (head.status() == 304) {
				if (expectedSha1Hex == null) throw new IOException("HTTP 304 without a sent If-None-Match");
				return new DocumentFetch(null, true);
			}
			if (head.status() == 200) {
				if (expectedSha1Hex == null) {
					consumeBody(head, destination, false, chunkCallback, null);
					return new DocumentFetch(destination, false);
				}
				// A conditional document's body hash is the ground truth, so a host that ignores the condition still reads as unchanged when the bytes match the expectation.
				MessageDigest hash = HashUtils.newSha1Digest();
				consumeBody(head, destination, false, chunkCallback, hash);
				if (HexFormat.of().formatHex(hash.digest()).equals(expectedSha1Hex)) return new DocumentFetch(destination, true);
				return new DocumentFetch(destination, false);
			}
			discardBody(head);
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

	private ResponseHead request(String path, String extraHeaders) throws IOException {
		out.write(requestLinePrefix);
		out.write(path.getBytes(StandardCharsets.UTF_8));
		out.write(requestLineSuffix);
		if (extraHeaders != null) out.write(extraHeaders.getBytes(StandardCharsets.UTF_8));
		out.write(CRLF);
		out.flush();
		return parseResponseHead();
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
		String etag = null;
		String location = null;
		boolean connectionClose = false;
		boolean chunked = false;
		int lines = 0;
		while (lines++ < MAX_HEADER_LINES) {
			String header = readLine();
			if (header.isEmpty()) return new ResponseHead(status, contentLength, contentRange, contentEncoding, etag, location, connectionClose, chunked);
			int colon = header.indexOf(':');
			if (colon <= 0) continue;
			String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = header.substring(colon + 1).trim();
			switch (name) {
				case "content-length" -> contentLength = parseContentLength(value, header);
				case "content-range" -> contentRange = value;
				case "content-encoding" -> contentEncoding = value;
				case "etag" -> etag = value;
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
		if (head.contentLength() != null) {
			transfer(destination, append, chunkCallback, hash, head.contentLength());
			if (head.connectionClose()) unhealthy = true;
			return;
		}
		if (head.status() == 200) {
			// Close-framed body from an exotic static host: read to EOF, and the connection dies with the response.
			unhealthy = true;
			transfer(destination, append, chunkCallback, hash, null);
			return;
		}
	}

	private void transfer(Path destination, boolean append, IntConsumer chunkCallback, MessageDigest hash, Long exactLength) throws IOException {
		byte[] buffer = new byte[DEFAULT_CHUNK_SIZE];
		long remaining = exactLength == null ? Long.MAX_VALUE : exactLength;
		try (OutputStream fos = destination == null ? null : append ? LocalFileWriter.openAppending(destination) : LocalFileWriter.open(destination)) {
			while (remaining > 0) {
				int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
				if (read < 0) {
					if (exactLength != null) throw new IOException("Response body ended before the promised Content-Length");
					return;
				}
				if (fos != null) fos.write(buffer, 0, read);
				if (hash != null) hash.update(buffer, 0, read);
				if (chunkCallback != null) chunkCallback.accept(read);
				remaining -= read;
			}
		}
	}

	private void discardBody(ResponseHead head) throws IOException {
		consumeBody(head, null, false, null, null);
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (Exception ignored) {
		}
	}
}
