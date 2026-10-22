package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;

import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.RevokingTrustManager;
import pl.skidam.automodpack_core.protocol.WireCodec;

/**
 * The one outbound HTTP pool: two shared clients (redirect-following and never) give every caller HTTP/2 multiplexing and
 * connection reuse instead of the per-call proxy connections raw HttpURLConnection produced. {@link #request} adds the
 * User-Agent, the timeout policy, the content codings and the retry discipline: exactly one retry, only on 429 (honoring
 * a seconds-form Retry-After clamped to the network timeout, so a hostile header cannot park a worker), any 5xx, or a
 * transport failure; every other answered status is a verdict that never retries, and so is a body past its size cap
 * or its deadline. The returned body is decoded, so
 * callers never see zstd or gzip bytes; the original Content-Encoding header stays visible for diagnostics.
 */
public final class HttpClientPool {

	/**
	 * Receipt: the Kinto OneCRL JSON is 823_876 bytes uncompressed / 139_449 gzip; Modrinth, CurseForge, DoH, Prism meta, and
	 * CA CRLs are smaller. 8 MiB is a tripwire past any good pool caller, applied to the wire bytes and again to the decoded
	 * bytes, since a CRL arrives over plain http and neither size is the server's to choose. File downloads use
	 * {@code HttpFileDownloader}.
	 */
	static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
	/**
	 * The request timeout covers the response head only. One deadline from the request start bounds the whole exchange
	 * and closes the body under its reader, so a trickling server cannot hold a caller, or the fetch every waiter of one
	 * URL shares, forever; twice the network timeout still reads a full {@link #MAX_BODY_BYTES} body at about 2.2 Mbit/s.
	 */
	static final Duration EXCHANGE_DEADLINE = NetUtils.NETWORK_TIMEOUT.multipliedBy(2);
	private static final ScheduledExecutorService EXCHANGE_DEADLINES = Executors.newSingleThreadScheduledExecutor(
			new CustomThreadFactoryBuilder().setNameFormat("AutoModpackHttpExchangeDeadline").setDaemon(true).build());
	private static final SSLContext HTTPS_CONTEXT = httpsContext();
	private static final HttpClient DIRECT_CLIENT = client(HttpClient.Redirect.NEVER);
	private static final HttpClient REDIRECT_CLIENT = client(HttpClient.Redirect.NORMAL);

	private HttpClientPool() {}

	/** The pooled client that never follows redirects: for requests whose credentials must not travel to a redirect target. */
	public static HttpClient direct() {
		return DIRECT_CLIENT;
	}

	/** The pooled redirect-following client: the default for downloads. */
	public static HttpClient redirects() {
		return REDIRECT_CLIENT;
	}

	/** One pooled API exchange; the body is fully read and decoded so the connection is reused. See the class doc for the retry discipline. */
	public static HttpResponse<byte[]> request(String url, Map<String, String> headers, byte[] body, boolean followRedirects) throws IOException {
		try {
			return requestWithRetry(url, headers, body, followRedirects ? REDIRECT_CLIENT : DIRECT_CLIENT);
		} catch (BodyRefusedException refused) {
			LOGGER.warn("{}: past AutoModpack's {} MiB / {} s cap, so this answer is unavailable; a CA CRL refused here leaves the server certificate's revocation undecided", refused.getMessage(),
					MAX_BODY_BYTES / (1024 * 1024), EXCHANGE_DEADLINE.toSeconds());
			throw refused;
		}
	}

	private static HttpResponse<byte[]> requestWithRetry(String url, Map<String, String> headers, byte[] body, HttpClient client) throws IOException {
		BufferedResponse response;
		try {
			response = exchange(url, headers, body, client);
		} catch (IOException transportFailure) {
			if (Thread.currentThread().isInterrupted() || transportFailure instanceof BodyRefusedException) throw transportFailure;
			LOGGER.warn("HTTP request to {} failed: {}, retrying once", url, transportFailure.toString());
			return decode(exchange(url, headers, body, client));
		}
		if (!retryable(response.statusCode())) return decode(response);
		LOGGER.warn("HTTP {} from {}, retrying once", response.statusCode(), url);
		if (response.statusCode() == 429) sleepOutRetryAfter(response.headers().firstValue("Retry-After").orElse(null));
		return decode(exchange(url, headers, body, client));
	}

	/** At most {@link #MAX_BODY_BYTES} from the stream; a longer body is a verdict, never retried. */
	private static byte[] readCapped(InputStream in, URI uri) throws IOException {
		byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
		if (bytes.length > MAX_BODY_BYTES) throw new BodyRefusedException("HTTP body from " + uri + " exceeded " + MAX_BODY_BYTES + " bytes");
		return bytes;
	}

	/** 429 and any 5xx deserve exactly one more try; every other status is the server's verdict. */
	private static boolean retryable(int statusCode) {
		return statusCode == 429 || statusCode >= 500;
	}

	/** Seconds-form Retry-After, clamped to the network timeout so a hostile header cannot park a worker. */
	private static void sleepOutRetryAfter(String retryAfter) throws IOException {
		if (retryAfter == null) return;
		long seconds;
		try {
			seconds = Long.parseLong(retryAfter.trim());
		} catch (NumberFormatException unparsable) {
			return;
		}
		if (seconds <= 0) return;
		try {
			Thread.sleep(Duration.ofSeconds(Math.min(seconds, NetUtils.NETWORK_TIMEOUT.toSeconds())).toMillis());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting out a Retry-After");
		}
	}

	private static BufferedResponse exchange(String url, Map<String, String> headers, byte[] body, HttpClient client) throws IOException {
		HttpRequest.Builder request;
		try {
			request = HttpRequest.newBuilder(URI.create(url));
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid request URI: " + url, e);
		}
		request.header("User-Agent", NetUtils.USER_AGENT).header("Accept-Encoding", WireCodec.offeredEncodings()).timeout(NetUtils.NETWORK_TIMEOUT);
		headers.forEach(request::setHeader);
		if (body != null) request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		else request.GET();
		ExchangeDeadline deadline = new ExchangeDeadline();
		ScheduledFuture<?> timer = EXCHANGE_DEADLINES.schedule(deadline::expire, EXCHANGE_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
		try {
			HttpResponse<InputStream> response;
			try {
				response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("HTTP request interrupted: " + url, e);
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				throw new IOException("HTTP client protocol error for " + url, e);
			}
			// The JDK body stream fails every read once closed, so a body the deadline cut never reads as a whole one.
			try (InputStream in = deadline.watch(response.body())) {
				return new BufferedResponse(response, readCapped(in, response.uri()));
			} catch (IOException e) {
				if (deadline.expired() && !(e instanceof BodyRefusedException)) throw deadline.refusal(response.uri());
				throw e;
			}
		} finally {
			timer.cancel(false);
		}
	}

	/** Whether the exchange deadline fired, and the body it closes when it does, whichever comes first. */
	private static final class ExchangeDeadline {
		private volatile boolean expired;
		private volatile InputStream body;

		InputStream watch(InputStream in) {
			body = in;
			if (expired) NetUtils.closeQuietly(in);
			return in;
		}

		void expire() {
			expired = true;
			InputStream in = body;
			if (in != null) NetUtils.closeQuietly(in);
		}

		boolean expired() {
			return expired;
		}

		BodyRefusedException refusal(URI uri) {
			return new BodyRefusedException("HTTP exchange with " + uri + " did not finish within " + EXCHANGE_DEADLINE.toSeconds() + " s");
		}
	}

	private static HttpResponse<byte[]> decode(BufferedResponse response) throws IOException {
		if (response.body().length == 0) return response;
		WireCodec codec = WireCodec.responseCoding(response.headers().firstValue("Content-Encoding").orElse(null));
		if (codec == null) return response;
		try (InputStream in = codec.unwrap(new ByteArrayInputStream(response.body()))) {
			return new BufferedResponse(response.original(), readCapped(in, response.uri()));
		}
	}

	/** A body the pool refuses by its own rule, size or deadline: a verdict on the answer, never retried. */
	private static final class BodyRefusedException extends IOException {
		BodyRefusedException(String message) {
			super(message);
		}
	}

	/** A response whose body was read in full; earlier responses of a redirect chain never carry one. */
	private record BufferedResponse(HttpResponse<InputStream> original, byte[] body) implements HttpResponse<byte[]> {
		@Override
		public int statusCode() {
			return original.statusCode();
		}

		@Override
		public HttpRequest request() {
			return original.request();
		}

		@Override
		public Optional<HttpResponse<byte[]>> previousResponse() {
			return original.previousResponse().map(previous -> new BufferedResponse(previous, new byte[0]));
		}

		@Override
		public HttpHeaders headers() {
			return original.headers();
		}

		@Override
		public Optional<SSLSession> sslSession() {
			return original.sslSession();
		}

		@Override
		public URI uri() {
			return original.uri();
		}

		@Override
		public HttpClient.Version version() {
			return original.version();
		}
	}

	private static HttpClient client(HttpClient.Redirect redirects) {
		// Daemon pool threads: a parked client must never hold the JVM open.
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).followRedirects(redirects).connectTimeout(NetUtils.NETWORK_TIMEOUT).sslContext(HTTPS_CONTEXT)
				.executor(Executors.newCachedThreadPool(new CustomThreadFactoryBuilder().setNameFormat("AutoModpackHttpClient-%d").setDaemon(true).build())).build();
	}

	private static SSLContext httpsContext() {
		try {
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, new TrustManager[]{RevokingTrustManager.wrap(null)}, new SecureRandom());
			return context;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to initialize HTTPS trust", e);
		}
	}
}
