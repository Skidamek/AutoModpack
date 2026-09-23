package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.platforms.CurseForgeAPI.CDN_HOST;
import static pl.skidam.automodpack_core.platforms.CurseForgeAPI.summonKey;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.protocol.LocalFileWriter;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.protocol.StaleRangeException;
import pl.skidam.automodpack_core.protocol.WireCodec;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.HttpClientPool;

public class HttpFileDownloader {

	private static final Logger LOGGER = LogManager.getLogger();

	// A download worker reuses one 512 KiB read buffer for every attempt instead of allocating half a MiB per call.
	private static final ThreadLocal<byte[]> READ_BUFFERS = ThreadLocal.withInitial(() -> new byte[NetUtils.READ_BUFFER_BYTES]);

	// The body-stall tripwire, the same receipt as the wire's TRANSFER_WRITE_STALL_TIMEOUT: a CDN body that delivers
	// zero bytes for 90 s is gone. A live link resets the window with every read - at the receipted drain floor
	// (~31 KB/s, the 20-client share of a 5 Mbps uplink) a 512 KiB READ_BUFFER_BYTES read completes every ~17 s, 5x
	// inside the window - so only a stalled or silently dropped body ever touches it. The request timeout covers the
	// response head only; tripping closes the body stream under its blocked reader, the read throws, and the platform
	// retry ladder recovers the attempt from the stored partial.
	private static final Duration BODY_STALL_TIMEOUT = NetUtils.TRANSFER_WRITE_STALL_TIMEOUT;
	// The fuse ticks six times inside its window, so a stall trips at most one tick past the 90 s line.
	private static final long STALL_FUSE_TICK_SECONDS = 15;

	/** The one daemon checker for every in-flight platform body; it lives as long as the JVM, like NET_EXECUTOR. */
	private static final ScheduledExecutorService BODY_STALL_WATCHDOG = Executors.newSingleThreadScheduledExecutor(
			new CustomThreadFactoryBuilder().setNameFormat("AutoModpackBodyStallFuse").setDaemon(true).build());

	/**
	 * Downloads a file from a URL to a target path using HTTP/2 if available.
	 * Blocks the calling thread (designed for use in Worker Threads).
	 *
	 * @param offset
	 *            The resume point: bytes before it already sit in the target and are not fetched again.
	 * @param progressAction
	 *            A callback to report bytes read (for bandwidth tracking).
	 * @throws IOException
	 *             If network or IO fails.
	 * @throws StaleRangeException
	 *             If the stored partial cannot serve as the resume prefix (the server cannot answer from the offset).
	 * @throws InterruptedException
	 *             If the download is cancelled.
	 */
	public void download(DownloadSource source, Path target, long offset, IntConsumer progressAction) throws IOException, InterruptedException {
		URI uri;
		try {
			uri = URI.create(source.url());
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid download URI", e);
		}

		boolean authenticate = isAuthenticatedCurseForgeTarget(source, uri);
		// The key-carrying request must never follow a redirect (DIRECT), so the explicit dance below re-sends it
		// without the key; every other source follows redirects in the pool like it always has.
		HttpResponse<InputStream> response = send(source, uri, authenticate, offset, authenticate ? HttpClientPool.direct() : HttpClientPool.redirects(), target);

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
			response = send(source, uri, false, offset, HttpClientPool.redirects(), target);
		}

		int statusCode = response.statusCode();
		if (statusCode == 416) {
			try (InputStream ignored = response.body()) {
				throw new StaleRangeException();
			}
		}
		long writeOffset = offset;
		if (statusCode == 206) {
			writeOffset = PartialResume.requireResumeStart(response.headers().firstValue("Content-Range").orElse(null), offset);
		} else if (statusCode != 200) {
			try (InputStream ignored = response.body()) {
				throw new HttpStatusException(statusCode);
			}
		} else if (offset > 0) {
			// A 200 to a Range request means "full representation": truncate and pull the whole body from zero, in place - the barebones-CDN case, not an error.
			LOGGER.info("Server ignored the Range header for {}; pulling the whole object from zero", target.getFileName());
			writeOffset = 0;
		}

		String encoding = response.headers().firstValue("Content-Encoding").orElse("").trim().toLowerCase(Locale.ROOT);
		WireCodec codec = WireCodec.negotiate(encoding);
		if (codec == null && !encoding.isEmpty()) throw new IOException("Unsupported Content-Encoding: " + encoding);

		try (InputStream rawIn = response.body();
				InputStream in = codec == null ? rawIn : codec.unwrap(rawIn);
				OutputStream out = writeOffset > 0 ? LocalFileWriter.openAt(target, writeOffset) : LocalFileWriter.open(target)) {

			AtomicLong lastProgressNanos = new AtomicLong(System.nanoTime());
			ScheduledFuture<?> stallFuse = armBodyStallFuse(rawIn, lastProgressNanos, target.getFileName());
			try {
				byte[] buffer = READ_BUFFERS.get();
				int bytesRead;
				while ((bytesRead = in.read(buffer)) != -1) {
					if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
					out.write(buffer, 0, bytesRead);
					lastProgressNanos.set(System.nanoTime());
					if (progressAction != null) progressAction.accept(bytesRead);
				}
			} finally {
				stallFuse.cancel(false);
			}
		}
	}

	/** The per-download body fuse: zero bytes for BODY_STALL_TIMEOUT closes the body stream under its blocked reader. */
	private static ScheduledFuture<?> armBodyStallFuse(InputStream body, AtomicLong lastProgressNanos, Object fileName) {
		return BODY_STALL_WATCHDOG.scheduleWithFixedDelay(() -> {
			if (System.nanoTime() - lastProgressNanos.get() < BODY_STALL_TIMEOUT.toNanos()) return;
			LOGGER.warn("The platform download body of {} delivered no bytes for {} s; closing it for the retry ladder", fileName, BODY_STALL_TIMEOUT.toSeconds());
			try {
				body.close();
			} catch (IOException ignored) {
			}
		}, STALL_FUSE_TICK_SECONDS, STALL_FUSE_TICK_SECONDS, TimeUnit.SECONDS);
	}

	private HttpResponse<InputStream> send(DownloadSource source, URI uri, boolean authenticate, long offset, HttpClient client, Path target)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder().uri(uri).header("User-Agent", NetUtils.USER_AGENT)
				.header("Accept-Encoding", WireCodec.offeredEncodings()).timeout(NetUtils.NETWORK_TIMEOUT).GET();
		if (authenticate) request.header("x-api-key", summonKey());
		if (offset > 0) request.header("Range", "bytes=" + offset + "-");

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
