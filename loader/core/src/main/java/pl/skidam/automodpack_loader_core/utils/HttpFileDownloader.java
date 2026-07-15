package pl.skidam.automodpack_loader_core.utils;

import static pl.skidam.automodpack_core.Constants.AM_VERSION;
import static pl.skidam.automodpack_core.platforms.CurseForgeAPI.CDN_HOST;
import static pl.skidam.automodpack_core.platforms.CurseForgeAPI.summonKey;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.zip.GZIPInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

public class HttpFileDownloader {

	private static final Logger LOGGER = LogManager.getLogger();

	// Shared Clients for HTTP/2 Multiplexing and Connection Pooling
	private static final HttpClient DIRECT_CLIENT = createClient(HttpClient.Redirect.NEVER);
	private static final HttpClient REDIRECT_CLIENT = createClient(HttpClient.Redirect.NORMAL);

	/**
	 * Downloads a file from a URL to a target path using HTTP/2 if available.
	 * Blocks the calling thread (designed for use in Worker Threads).
	 *
	 * @param source
	 *            The source URL and provider.
	 * @param target
	 *            The destination file path.
	 * @param progressAction
	 *            A callback to report bytes read (for bandwidth tracking).
	 * @throws IOException
	 *             If network or IO fails.
	 * @throws InterruptedException
	 *             If the download is cancelled.
	 */
	public void download(DownloadSource source, Path target, IntConsumer progressAction) throws IOException, InterruptedException {
		SmartFileUtils.createParentDirs(target);

		URI uri;
		try {
			uri = URI.create(source.url());
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid download URI", e);
		}

		boolean authenticate = isAuthenticatedCurseForgeTarget(source, uri);
		HttpResponse<InputStream> response = send(source, uri, authenticate, authenticate ? DIRECT_CLIENT : REDIRECT_CLIENT, target);

		if (authenticate && response.statusCode() >= 300 && response.statusCode() < 400) {
			try (InputStream ignored = response.body()) {
				String location = response.headers().firstValue("Location").orElseThrow(() -> new IOException("HTTP redirect missing Location header"));
				try {
					uri = uri.resolve(location);
				} catch (IllegalArgumentException e) {
					throw new IOException("Invalid HTTP redirect URI", e);
				}
				if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IOException("Refusing CurseForge HTTPS downgrade redirect");
			}
			response = send(source, uri, false, REDIRECT_CLIENT, target);
		}

		int statusCode = response.statusCode();
		if (statusCode != 200) {
			try (InputStream ignored = response.body()) {
				throw new HttpStatusException(statusCode);
			}
		}

		boolean isGzip = "gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""));

		try (InputStream rawIn = response.body();
				InputStream in = isGzip ? new GZIPInputStream(rawIn) : rawIn;
				OutputStream out = new BufferedOutputStream(new FileOutputStream(target.toFile()), 64 * 1024)) {

			byte[] buffer = new byte[NetUtils.DEFAULT_CHUNK_SIZE];
			int bytesRead;
			while ((bytesRead = in.read(buffer)) != -1) {
				if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
				out.write(buffer, 0, bytesRead);

				if (progressAction != null) { progressAction.accept(bytesRead); }
			}
		}
	}

	private HttpResponse<InputStream> send(DownloadSource source, URI uri, boolean authenticate, HttpClient client, Path target)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder().uri(uri).header("User-Agent", "github/skidamek/automodpack/" + AM_VERSION)
				.header("Accept-Encoding", "gzip").timeout(Duration.ofSeconds(10)).GET();
		if (authenticate) { request.header("x-api-key", summonKey()); }

		try {
			HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
			LOGGER.info("HTTPS Download {}: Provider={} Host={} Protocol={} Status={}", target.getFileName(), source.provider(), uri.getHost(),
					response.version(), response.statusCode());
			return response;
		} catch (InterruptedException | IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("HTTP Client Protocol Error", e);
		}
	}

	private static HttpClient createClient(HttpClient.Redirect redirects) {
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).followRedirects(redirects).connectTimeout(Duration.ofSeconds(10))
				.executor(Executors.newCachedThreadPool()).build();
	}

	private static boolean isAuthenticatedCurseForgeTarget(DownloadSource source, URI uri) {
		return source.provider() == DownloadSource.Provider.CURSEFORGE && "https".equalsIgnoreCase(uri.getScheme()) && CDN_HOST.equalsIgnoreCase(uri.getHost())
				&& uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
	}

	public static class HttpStatusException extends IOException {
		private final int statusCode;

		HttpStatusException(int statusCode) {
			super("HTTP request failed with status " + statusCode);
			this.statusCode = statusCode;
		}

		public int statusCode() {
			return statusCode;
		}
	}
}
