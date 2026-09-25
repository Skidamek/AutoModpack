package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import pl.skidam.automodpack_core.protocol.NetUtils;

/**
 * The one outbound HTTP pool: two shared clients (redirect-following and never) give every caller HTTP/2 multiplexing and
 * connection reuse instead of the per-call proxy connections raw HttpURLConnection produced. {@link #request} adds the
 * User-Agent, the timeout policy and the retry discipline: exactly one retry, only on 429 (honoring a seconds-form
 * Retry-After clamped to the network timeout, so a hostile header cannot park a worker), any 5xx, or a transport failure;
 * every other answered status is a verdict that never retries.
 */
public final class HttpClientPool {

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

	/** One pooled API exchange; the body is fully read so the connection is reused. See the class doc for the retry discipline. */
	public static HttpResponse<byte[]> request(String url, Map<String, String> headers, byte[] body, boolean followRedirects) throws IOException {
		HttpClient client = followRedirects ? REDIRECT_CLIENT : DIRECT_CLIENT;
		HttpResponse<byte[]> response;
		try {
			response = exchange(url, headers, body, client);
		} catch (IOException transportFailure) {
			if (Thread.currentThread().isInterrupted()) throw transportFailure;
			LOGGER.warn("HTTP request to {} failed: {}, retrying once", url, transportFailure.toString());
			return exchange(url, headers, body, client);
		}
		if (!retryable(response.statusCode())) return response;
		LOGGER.warn("HTTP {} from {}, retrying once", response.statusCode(), url);
		if (response.statusCode() == 429) sleepOutRetryAfter(response.headers().firstValue("Retry-After").orElse(null));
		return exchange(url, headers, body, client);
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

	private static HttpResponse<byte[]> exchange(String url, Map<String, String> headers, byte[] body, HttpClient client) throws IOException {
		HttpRequest.Builder request;
		try {
			request = HttpRequest.newBuilder(URI.create(url));
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid request URI: " + url, e);
		}
		request.header("User-Agent", NetUtils.USER_AGENT).timeout(NetUtils.NETWORK_TIMEOUT);
		headers.forEach(request::setHeader);
		if (body != null) request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		else request.GET();
		try {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("HTTP request interrupted: " + url, e);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("HTTP client protocol error for " + url, e);
		}
	}

	private static HttpClient client(HttpClient.Redirect redirects) {
		// Daemon pool threads: a parked client must never hold the JVM open.
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).followRedirects(redirects).connectTimeout(NetUtils.NETWORK_TIMEOUT)
				.executor(Executors.newCachedThreadPool(new CustomThreadFactoryBuilder().setNameFormat("AutoModpackHttpClient-%d").setDaemon(true).build())).build();
	}
}
