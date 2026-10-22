package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The one cache for fetched revocation data, the Mozilla OneCRL feed and CA CRLs: the last usable body of each URL
 * on disk, refetched through {@link HttpClientPool} once it is older than {@link #MAX_AGE}. A failed refetch keeps the
 * old copy, so the caller's own rule (the bundled OneCRL floor, a CRL's nextUpdate) decides what an old copy is still
 * worth. Concurrent requests for one URL share a single fetch. Without a directory nothing is kept between fetches.
 */
public final class FetchCache {

	public static final Duration MAX_AGE = Duration.ofHours(6);
	private static final ExecutorService FETCHES = Executors.newCachedThreadPool(new CustomThreadFactoryBuilder().setNameFormat("AutoModpackFetchCache-%d").setDaemon(true).build());
	private static volatile FetchCache shared = new FetchCache(null);

	private final Path directory;
	private final Map<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();

	public FetchCache(Path directory) {
		this.directory = directory;
	}

	/** The process-wide cache; memory-less until the loader entrypoint names its directory under the data root. */
	public static FetchCache shared() {
		return shared;
	}

	public static void useDirectory(Path directory) {
		shared = new FetchCache(directory);
	}

	/**
	 * The parsed body kept under the first URL: the disk copy while it is younger than {@link #MAX_AGE}, otherwise a
	 * fetch that races every URL and stores the first body {@code parse} accepts, cancelling the rest. A fetch that
	 * fails, or is still running after {@code wait}, resolves to the old copy, or null when there is none.
	 * {@code parse} returns null, or throws, for a body it cannot use; the future never fails.
	 */
	public <T> CompletableFuture<T> get(List<String> urls, Function<byte[], T> parse, Duration wait) {
		Path file = file(urls.get(0));
		if (file != null && isFresh(file)) {
			T stored = parseQuietly(read(file), parse);
			if (stored != null) return CompletableFuture.completedFuture(stored);
		}
		String key = urls.get(0);
		CompletableFuture<byte[]> created = new CompletableFuture<>();
		CompletableFuture<byte[]> fetch = inFlight.putIfAbsent(key, created);
		if (fetch == null) {
			fetch = created;
			FETCHES.execute(() -> {
				try {
					created.complete(fetch(urls, parse, file));
				} finally {
					inFlight.remove(key, created);
					created.complete(null);
				}
			});
		}
		return fetch.copy().completeOnTimeout(null, wait.toMillis(), TimeUnit.MILLISECONDS).thenApplyAsync(body -> parseQuietly(body != null ? body : file == null ? null : read(file), parse), FETCHES);
	}

	/** The body kept under {@code url} whatever its age, or null; a caller judges a fetched body against it. */
	public byte[] stored(String url) {
		Path file = file(url);
		return file == null ? null : read(file);
	}

	/** Races every URL and keeps the first body {@code parse} accepts; a rejected or failed answer never ends the race. */
	private <T> byte[] fetch(List<String> urls, Function<byte[], T> parse, Path file) {
		CompletableFuture<byte[]> winner = new CompletableFuture<>();
		List<String> failures = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger pending = new AtomicInteger(urls.size());
		List<Future<?>> attempts = new ArrayList<>();
		for (String url : urls) {
			attempts.add(FETCHES.submit(() -> {
				try {
					String failure = attempt(url, parse, winner);
					if (failure != null) failures.add(failure);
				} finally {
					if (pending.decrementAndGet() == 0) winner.complete(null);
				}
			}));
		}
		byte[] body = winner.join();
		attempts.forEach(attempt -> attempt.cancel(true));
		if (body != null) {
			store(file, body);
			return body;
		}
		LOGGER.warn("Could not refresh {}: {}; {}", urls.get(0), String.join(", ", failures), file != null && Files.isRegularFile(file) ? "keeping the cached copy" : "no cached copy");
		return null;
	}

	/** One URL's answer: completes {@code winner} with an accepted body, otherwise names the failure. */
	private static <T> String attempt(String url, Function<byte[], T> parse, CompletableFuture<byte[]> winner) {
		try {
			HttpResponse<byte[]> response = HttpClientPool.request(url, Map.of(), null, true);
			if (response.statusCode() < 200 || response.statusCode() >= 300) return "HTTP " + response.statusCode();
			if (parseQuietly(response.body(), parse) == null) return "an unusable body";
			winner.complete(response.body());
			return null;
		} catch (IOException | RuntimeException e) {
			return e.toString();
		}
	}

	private static void store(Path file, byte[] body) {
		if (file == null) return;
		try {
			DurableFiles.writeVolatile(file, body);
		} catch (IOException e) {
			LOGGER.warn("Could not cache {}: {}", file, e.toString());
		}
	}

	private Path file(String url) {
		if (directory == null) return null;
		try {
			return directory.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static boolean isFresh(Path file) {
		try {
			return Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(MAX_AGE));
		} catch (IOException e) {
			return false;
		}
	}

	private static byte[] read(Path file) {
		try {
			return Files.readAllBytes(file);
		} catch (IOException e) {
			return null;
		}
	}

	private static <T> T parseQuietly(byte[] body, Function<byte[], T> parse) {
		if (body == null) return null;
		try {
			return parse.apply(body);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
