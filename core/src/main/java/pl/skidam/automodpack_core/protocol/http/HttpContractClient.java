package pl.skidam.automodpack_core.protocol.http;

import static pl.skidam.automodpack_core.protocol.NetUtils.NETWORK_TIMEOUT_MILLIS;
import static pl.skidam.automodpack_core.protocol.NetUtils.PRE_CONFIGURATION_KEEPALIVE_INTERVAL;
import static pl.skidam.automodpack_core.protocol.NetUtils.TRANSFER_IDLE_TIMEOUT_MILLIS;
import static pl.skidam.automodpack_core.protocol.NetUtils.USER_AGENT;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.IntConsumer;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.protocol.CandidateTrustValidation;
import pl.skidam.automodpack_core.protocol.CustomizableTrustManager;
import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.LocalFileWriter;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.protocol.StaleRangeException;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The netty-free client side of the URL contract: JDK {@link HttpsURLConnection} GETs against {@code /head},
 * {@code /journal}, and {@code /objects/<sha1>} over the same pinned TLS as the custom protocol, so it runs from
 * preload where netty is absent. There is no internal thread pool and no global JVM tuning ({@code http.maxConnections}
 * is never written): concurrency arrives from DownloadManager's worker pool (5 workers sit inside the URL contract's
 * 4-8 connection pool) and keep-alive comes free from the JDK's connection cache (5 idle connections per host by
 * default), so the pool arithmetic is the caller's, never this class's. Stateless like the contract: documents carry
 * {@code If-None-Match}, objects carry {@code Range}, and correctness never depends on the server honoring either -
 * the body hash is the ground truth, 304 and 206 are only optimizations.
 */
public final class HttpContractClient implements PackTransport {

	/** The document verdict for one response; the body hash decides, never the status alone. */
	enum DocumentFetchDecision {
		UNCHANGED_FROM_LOCAL, UNCHANGED_FROM_BODY, FETCHED, FAILED
	}

	/** How a response's body lands in the destination file. */
	enum ObjectWriteMode {
		APPEND, REPLACE, STALE_RANGE, FAILED
	}

	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final SSLContext sslContext;
	private final Set<HttpURLConnection> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private volatile boolean closed;

	private HttpContractClient(ConnectionJsons.ConnectionInfo connectionInfo, SSLContext sslContext) {
		this.connectionInfo = connectionInfo;
		this.sslContext = sslContext;
	}

	/** Opens the transport by running the shared trust ladder over one probe handshake; on acceptance the pin lives in the returned context. */
	public static CompletableFuture<HttpContractClient> createAsync(ConnectionJsons.ConnectionInfo connectionInfo, Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		if (connectionInfo == null || !connectionInfo.isComplete())
			return CompletableFuture.failedFuture(new IllegalArgumentException("Connection origin or endpoint is missing"));

		return CompletableFuture.supplyAsync(() -> {
			try {
				return openProbe(connectionInfo, trustCallback);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, DownloadClient.NET_EXECUTOR).thenCompose(probe -> CandidateTrustValidation
				.validate(new CandidateTrustValidation.Candidate(probe.socket(), probe.trustManager(), probe.sessionTrust(), connectionInfo.origin.getHostString(),
						connectionInfo.endpoint.getHostString(), trustCallback, () -> true), PRE_CONFIGURATION_KEEPALIVE_INTERVAL)
				.handle((ignored, error) -> {
					// The probe's job is done either way: an accepted trust lives in the SSLContext, not in this socket.
					closeQuietly(probe.socket());
					if (error != null) throw error instanceof RuntimeException runtime ? runtime : new CompletionException(error);
					return new HttpContractClient(connectionInfo, probe.context());
				}));
	}

	private record Probe(SSLContext context, SSLSocket socket, CustomizableTrustManager trustManager, CustomizableTrustManager.SessionTrust sessionTrust) {}

	private static Probe openProbe(ConnectionJsons.ConnectionInfo connectionInfo, Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) throws IOException {
		String endpointHost = connectionInfo.endpoint.getHostString();
		InetSocketAddress address = new InetSocketAddress(endpointHost, connectionInfo.endpoint.getPort());
		if (address.isUnresolved()) throw new IOException("Failed to resolve endpoint host: " + endpointHost);

		CustomizableTrustManager.SessionTrust sessionTrust = new CustomizableTrustManager.SessionTrust(AddressHelpers.formatAddress(connectionInfo.origin),
				connectionInfo.expectedFingerprint);
		CustomizableTrustManager trustManager;
		try {
			trustManager = new CustomizableTrustManager(sessionTrust, null);
		} catch (Exception e) {
			throw new IOException("Failed to initialize certificate trust", e);
		}
		SSLContext context = CandidateTrustValidation.newSslContext(trustManager);

		Socket probe = new Socket();
		try {
			probe.connect(address, NETWORK_TIMEOUT_MILLIS);
			// Helps plain TCP NAT mappings survive the parked trust decision; zero protocol impact.
			probe.setKeepAlive(true);
		} catch (IOException e) {
			closeQuietly(probe);
			throw e;
		}
		// Closes the probe itself when the handshake fails.
		SSLSocket tlsProbe = CandidateTrustValidation.wrapWithTls(probe, context, connectionInfo.origin.getHostString(), connectionInfo.endpoint.getPort());
		return new Probe(context, tlsProbe, trustManager, sessionTrust);
	}

	@Override
	public CompletableFuture<DocumentFetch> downloadDocument(byte[] key, Path destination, String expectedSha1Hex, IntConsumer progress) {
		return submit(() -> {
			HttpURLConnection connection = openConnection(documentPath(new String(key, StandardCharsets.UTF_8)));
			boolean reusable = false;
			try {
				if (expectedSha1Hex != null) connection.setRequestProperty("If-None-Match", quoteEtag(expectedSha1Hex));
				int status = connection.getResponseCode();
				// A 304 only counts when we actually asked conditionally; the body hash decides over a 200.
				String receivedSha1 = null;
				if (status == 200) receivedSha1 = hashBody(connection, destination, false, progress);
				else if (status == 304) drain(connection);
				DocumentFetch fetch = switch (decideDocument(status, expectedSha1Hex, receivedSha1)) {
					case UNCHANGED_FROM_LOCAL -> new DocumentFetch(null, true);
					case UNCHANGED_FROM_BODY -> new DocumentFetch(destination, true);
					case FETCHED -> new DocumentFetch(destination, false);
					case FAILED -> throw new IOException("HTTP " + status);
				};
				reusable = true;
				return fetch;
			} finally {
				inFlight.remove(connection);
				finish(connection, reusable);
			}
		});
	}

	@Override
	public CompletableFuture<Path> downloadFile(byte[] key, Path destination, long offset, IntConsumer progress) {
		return submit(() -> {
			HttpURLConnection connection = openConnection(objectPath(new String(key, StandardCharsets.UTF_8)));
			boolean reusable = false;
			try {
				String range = rangeHeaderValue(offset);
				if (range != null) connection.setRequestProperty("Range", range);
				int status = connection.getResponseCode();
				switch (decideObject(status, range != null)) {
					// A server that ignores Range answers 200 with the full body; the truncate is the correct result then.
					case APPEND -> {
						requireResumeStart(connection, offset);
						streamBody(connection, destination, true, progress);
					}
					case REPLACE -> streamBody(connection, destination, false, progress);
					case STALE_RANGE -> throw new StaleRangeException();
					case FAILED -> throw new IOException("HTTP " + status);
				}
				reusable = true;
				return destination;
			} finally {
				inFlight.remove(connection);
				finish(connection, reusable);
			}
		});
	}

	/** Reads the bodyless 304 to its end so the JDK can return the connection to its keep-alive cache. */
	private static void drain(HttpURLConnection connection) throws IOException {
		try (InputStream ignored = connection.getInputStream()) {
		}
	}

	/**
	 * A 206 may only be appended behind the stored prefix when the server actually resumed at the requested offset;
	 * anything else fails fast instead of splicing together bytes that promotion would only reject after the fact.
	 */
	private static void requireResumeStart(HttpURLConnection connection, long offset) throws IOException {
		String contentRange = connection.getHeaderField("Content-Range");
		if (contentRange == null) throw new IOException("HTTP 206 without a Content-Range header");
		String spec = contentRange.trim();
		if (!spec.startsWith("bytes ")) throw new IOException("Unparseable Content-Range: " + contentRange);
		String first = spec.substring("bytes ".length(), spec.indexOf('-')).trim();
		long start;
		try {
			start = Long.parseLong(first);
		} catch (NumberFormatException e) {
			throw new IOException("Unparseable Content-Range: " + contentRange);
		}
		if (start != offset) throw new IOException("Server resumed at byte " + start + " while the stored prefix ends at " + offset);
	}

	/**
	 * Disconnecting a fully-read response closes its socket outright, so every completed request must skip it and stay
	 * in the JDK's keep-alive cache - the latency of the whole parallel-fetch story. Only failed transfers pay it.
	 */
	private static void finish(HttpURLConnection connection, boolean reusable) {
		if (!reusable) connection.disconnect();
	}

	@Override
	public void abortTransfers() {
		for (HttpURLConnection connection : inFlight)
			connection.disconnect();
		inFlight.clear();
	}

	@Override
	public void close() {
		closed = true;
		abortTransfers();
	}

	/** The document verdict: 304 needs a sent expectation, and a 200 whose body hashes to the expectation is unchanged too. */
	static DocumentFetchDecision decideDocument(int statusCode, String expectedSha1Hex, String receivedSha1Hex) {
		if (statusCode == 304) return expectedSha1Hex != null ? DocumentFetchDecision.UNCHANGED_FROM_LOCAL : DocumentFetchDecision.FAILED;
		if (statusCode == 200) return expectedSha1Hex != null && expectedSha1Hex.equals(receivedSha1Hex) ? DocumentFetchDecision.UNCHANGED_FROM_BODY : DocumentFetchDecision.FETCHED;
		return DocumentFetchDecision.FAILED;
	}

	/** The object verdict: 206 appends behind the stored prefix, 200 replaces it, 416 means the prefix is beyond the object. */
	static ObjectWriteMode decideObject(int statusCode, boolean ranged) {
		if (statusCode == 206) return ObjectWriteMode.APPEND;
		if (statusCode == 200) return ObjectWriteMode.REPLACE;
		// Only a request that actually carried a Range can receive a meaningful "not satisfiable"; a 416 without one is
		// just another failure, not a verdict about a stored prefix.
		if (statusCode == 416 && ranged) return ObjectWriteMode.STALE_RANGE;
		return ObjectWriteMode.FAILED;
	}

	static String documentPath(String key) {
		return "/" + key;
	}

	static String objectPath(String sha1Hex) {
		return "/objects/" + sha1Hex;
	}

	/** The single open-ended range shape the client sends; no range for a fresh download. */
	static String rangeHeaderValue(long offset) {
		return offset <= 0 ? null : "bytes=" + offset + "-";
	}

	static String quoteEtag(String sha1Hex) {
		return "\"" + sha1Hex + "\"";
	}

	private static String hashBody(HttpURLConnection connection, Path destination, boolean append, IntConsumer progress) throws IOException {
		MessageDigest digest = HashUtils.newSha1Digest();
		try (InputStream in = connection.getInputStream(); OutputStream out = append ? LocalFileWriter.openAppending(destination) : LocalFileWriter.open(destination)) {
			byte[] buffer = new byte[NetUtils.DEFAULT_CHUNK_SIZE];
			int read;
			while ((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
				out.write(buffer, 0, read);
				if (progress != null) progress.accept(read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void streamBody(HttpURLConnection connection, Path destination, boolean append, IntConsumer progress) throws IOException {
		try (InputStream in = connection.getInputStream(); OutputStream out = append ? LocalFileWriter.openAppending(destination) : LocalFileWriter.open(destination)) {
			byte[] buffer = new byte[NetUtils.DEFAULT_CHUNK_SIZE];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				if (progress != null) progress.accept(read);
			}
		}
	}

	private HttpURLConnection openConnection(String path) throws IOException {
		String host = connectionInfo.endpoint.getHostString();
		// Bracket IPv6 literals so the URL parses; the origin never carries a scheme, the mode name implies HTTPS.
		URL url = URI.create("https://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + connectionInfo.endpoint.getPort() + path).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		if (!(connection instanceof HttpsURLConnection https)) {
			connection.disconnect();
			throw new IOException("The URL contract is HTTPS-only: " + path);
		}
		https.setSSLSocketFactory(sslContext.getSocketFactory());
		// Identity here is the pinned certificate, not a name: self-signed pins carry no hostname to verify, and the
		// trust ladder already authenticated the peer, so standard hostname verification is intentionally replaced.
		https.setHostnameVerifier((hostname, session) -> true);
		https.setRequestMethod("GET");
		https.setConnectTimeout(NETWORK_TIMEOUT_MILLIS);
		// HttpURLConnection offers one read deadline for headers and body alike, so it runs at the transfer-idle grade
		// instead of splitting a connect-grade header window from a transfer-grade body window.
		https.setReadTimeout(TRANSFER_IDLE_TIMEOUT_MILLIS);
		// The contract has no redirects; a 3xx fails like any other non-200/206.
		https.setInstanceFollowRedirects(false);
		https.setRequestProperty("User-Agent", USER_AGENT);
		inFlight.add(https);
		return https;
	}

	private <T> CompletableFuture<T> submit(Transfer<T> transfer) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				if (closed) throw new IOException("HTTP transport is closed");
				return transfer.run();
			} catch (Exception e) {
				throw e instanceof CompletionException completion ? completion : new CompletionException(e);
			}
		}, DownloadClient.NET_EXECUTOR);
	}

	@FunctionalInterface
	private interface Transfer<T> {
		T run() throws Exception;
	}

	private static void closeQuietly(AutoCloseable closeable) {
		try {
			closeable.close();
		} catch (Exception ignored) {
		}
	}
}
