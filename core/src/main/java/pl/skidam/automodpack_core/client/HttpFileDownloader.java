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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PartialResume;
import pl.skidam.automodpack_core.protocol.StaleRangeException;
import pl.skidam.automodpack_core.protocol.WireCodec;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.HttpClientPool;
import pl.skidam.automodpack_core.utils.RateFuse;

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
	// The watchdog's own cadence: the stall fuse reads it as "no progress since the last tick" and the trickle fuse as
	// its window length, so one tick measures exactly one rate window.
	private static final long STALL_FUSE_TICK_SECONDS = 15;
	// The rate window beside the stall tripwire is the watchdog's own tick, so one tick measures exactly one window. The
	// floor is the codebase's one canonical trickle floor (TAKE_RATE_FLOOR_BYTES_PER_SECOND, receipted on NetUtils), used by
	// both transports' fuses so there is a single number to reason about; as a per-body tripwire it sits far under anything
	// healthy - the slowest plausible client link, 1 Mbps split across this run's PLATFORM_WORKERS bodies, still averages
	// ~25 KB/s per body - so only a genuinely stalled or dripping body ever reaches it. Two consecutive windows under it
	// corroborate a trickle: at 4 KiB/s one 15 s window still carries ~60 KB of sample, 8x the read granularity, so read
	// timing cannot fake a slow body.
	private static final int CORROBORATING_WINDOWS = 2;

	/** The one daemon checker for every in-flight platform body; it lives as long as the JVM, like NET_EXECUTOR. */
	private static final ScheduledExecutorService BODY_STALL_WATCHDOG = Executors.newSingleThreadScheduledExecutor(
			new CustomThreadFactoryBuilder().setNameFormat("AutoModpackBodyStallFuse").setDaemon(true).build());

	/**
	 * Downloads a file from a URL to a target path using HTTP/2 if available, with the client-side fuses armed.
	 * Blocks the calling thread (designed for use in Worker Threads).
	 *
	 * @param fileSize
	 *            Advertised object size; tiles fill {@code target} as {@code staging/<sha1>/}.
	 * @param offset
	 *            The first missing object byte; bytes before it already sit in finished tiles.
	 * @param progressAction
	 *            A callback to report bytes read (for bandwidth tracking).
	 * @param rateFuseFloorBytesPerSecond
	 *            The trickle fuse floor over pre-decode body bytes; 0 keeps it off. Arm it only when a reroute exists
	 *            behind the burned attempt.
	 * @param control
	 *            The external cancel handle this attempt attaches its body to; null when nobody cancels in flight.
	 * @throws StaleRangeException
	 *             If the stored partial cannot serve as the resume prefix (the server cannot answer from the offset).
	 * @throws ReroutedAttemptException
	 *             If somebody cancelled the attempt: the rescue's own verdict, not a failure of the source.
	 */
	public void download(DownloadSource source, Path target, long fileSize, long offset, IntConsumer progressAction, long rateFuseFloorBytesPerSecond, AttemptControl control)
			throws IOException, InterruptedException {
		URI uri;
		try {
			uri = URI.create(source.url());
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid download URI", e);
		}

		boolean authenticate = isAuthenticatedCurseForgeTarget(source, uri);
		// The key-carrying request must never follow a redirect (DIRECT), so the explicit dance below re-sends it
		// without the key; every other source follows redirects in the pool like it always has.
		long stopAt = PartialResume.stopBefore(target, offset, fileSize);
		HttpResponse<InputStream> response = send(source, uri, authenticate, offset, stopAt, fileSize, authenticate ? HttpClientPool.direct() : HttpClientPool.redirects(), target);

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
			response = send(source, uri, false, offset, stopAt, fileSize, HttpClientPool.redirects(), target);
		}

		int statusCode = response.statusCode();
		if (statusCode == 416) {
			try (InputStream ignored = response.body()) {
				PartialResume.delete(target);
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
			LOGGER.debug("Server ignored the Range header for {}; pulling the whole object from zero", target.getFileName());
			PartialResume.delete(target);
			writeOffset = 0;
		}

		WireCodec codec = WireCodec.responseCoding(response.headers().firstValue("Content-Encoding").orElse(null));

		try (InputStream rawIn = response.body()) {
			AtomicLong lastProgressNanos = new AtomicLong(System.nanoTime());
			RateFuse trickleFuse = new RateFuse(rateFuseFloorBytesPerSecond, TimeUnit.SECONDS.toNanos(STALL_FUSE_TICK_SECONDS), CORROBORATING_WINDOWS, System::nanoTime, () -> {
				LOGGER.warn("The platform download body of {} stayed under the {} B/s trickle floor across two windows; closing it for the retry ladder", target.getFileName(), rateFuseFloorBytesPerSecond);
				NetUtils.closeQuietly(rawIn);
			});
			ScheduledFuture<?> stallFuse = armBodyStallFuse(rawIn, lastProgressNanos, trickleFuse, target.getFileName());
			if (control != null) control.attach(rawIn);
			long written = writeOffset;
			WireByteCounter counted = new WireByteCounter(rawIn, trickleFuse::onBytes);
			try (InputStream in = codec == null ? counted : codec.unwrap(counted); OutputStream out = PartialResume.writer(target, fileSize, writeOffset)) {
				byte[] buffer = READ_BUFFERS.get();
				int bytesRead;
				while ((bytesRead = in.read(buffer)) != -1) {
					if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
					out.write(buffer, 0, bytesRead);
					written += bytesRead;
					lastProgressNanos.set(System.nanoTime());
					if (progressAction != null) progressAction.accept(bytesRead);
				}
			} catch (IOException e) {
				// A cancelled body reads as a closed stream: the rescue's verdict, never the remote source's.
				if (control != null && control.cancelled()) throw new ReroutedAttemptException(source.url());
				throw e;
			} finally {
				stallFuse.cancel(false);
			}
			if (control != null && control.cancelled()) throw new ReroutedAttemptException(source.url());
			long expectedEnd = Math.min(stopAt, fileSize);
			if (written < expectedEnd) throw new IOException("Platform body ended at " + written + " of " + expectedEnd + " bytes for " + target.getFileName());
		}
	}

	/**
	 * The per-download body fuse: zero bytes for BODY_STALL_TIMEOUT closes the body stream under its blocked reader, and
	 * the trickle fuse beside it closes it the same way once the body drained under its floor for CORROBORATING_WINDOWS
	 * windows. Pure-zero stretches stay owned by the stall fuse.
	 */
	private static ScheduledFuture<?> armBodyStallFuse(InputStream body, AtomicLong lastProgressNanos, RateFuse trickleFuse, Object fileName) {
		return BODY_STALL_WATCHDOG.scheduleWithFixedDelay(() -> {
			if (System.nanoTime() - lastProgressNanos.get() < BODY_STALL_TIMEOUT.toNanos()) {
				trickleFuse.tick();
				return;
			}
			LOGGER.warn("The platform download body of {} delivered no bytes for {} s; closing it for the retry ladder", fileName, BODY_STALL_TIMEOUT.toSeconds());
			NetUtils.closeQuietly(body);
		}, STALL_FUSE_TICK_SECONDS, STALL_FUSE_TICK_SECONDS, TimeUnit.SECONDS);
	}

	private HttpResponse<InputStream> send(DownloadSource source, URI uri, boolean authenticate, long offset, long stopAt, long fileSize, HttpClient client, Path target) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder().uri(uri).header("User-Agent", NetUtils.USER_AGENT)
				.header("Accept-Encoding", WireCodec.offeredEncodings()).timeout(NetUtils.NETWORK_TIMEOUT).GET();
		if (authenticate) request.header("x-api-key", summonKey());
		if (offset > 0 || stopAt < fileSize) request.header("Range", "bytes=" + offset + "-" + (stopAt < fileSize ? stopAt - 1 : ""));

		try {
			HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
			LOGGER.debug("HTTPS Download {}: Provider={} Host={} Protocol={} Status={}", target.getFileName(), source.provider(), uri.getHost(),
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

	/** Hands the fuse the body's pre-decode byte count, so it judges the wire and not the decompressor's output. */
	private static final class WireByteCounter extends FilterInputStream {
		private final IntConsumer onBytes;

		WireByteCounter(InputStream in, IntConsumer onBytes) {
			super(in);
			this.onBytes = onBytes;
		}

		@Override
		public int read() throws IOException {
			int read = super.read();
			if (read != -1) onBytes.accept(1);
			return read;
		}

		@Override
		public int read(byte[] buffer, int byteOffset, int byteLength) throws IOException {
			int read = super.read(buffer, byteOffset, byteLength);
			if (read != -1) onBytes.accept(read);
			return read;
		}
	}

	/** A handle on the in-flight attempt's body: attached by the downloader, cancelled from outside to close the body under its blocked reader. */
	public static final class AttemptControl {
		private final AtomicReference<InputStream> body = new AtomicReference<>();
		// Latched so a cancel racing the body open is not lost: whoever comes second closes. Without it a gate cancel
		// landing before attach() is a no-op and that attempt crawls on, unrescued and unflagged.
		private volatile boolean cancelled;

		private void attach(InputStream body) {
			this.body.set(body);
			if (cancelled) NetUtils.closeQuietly(body);
		}

		/** True once somebody cancelled this attempt, so the closed stream reads as a reroute instead of a remote failure. */
		public boolean cancelled() {
			return cancelled;
		}

		/** Closes the attached body; the blocked read throws and the attempt ends as a reroute, never as a remote failure. */
		public void cancel() {
			cancelled = true;
			InputStream body = this.body.get();
			if (body != null) NetUtils.closeQuietly(body);
		}
	}

	/** The rescue moved this attempt off its platform domain on purpose: no failure, so nothing is charged against it. */
	public static class ReroutedAttemptException extends IOException {
		public ReroutedAttemptException(String url) {
			super("Platform download of " + url + " was cancelled for a faster route");
		}
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
