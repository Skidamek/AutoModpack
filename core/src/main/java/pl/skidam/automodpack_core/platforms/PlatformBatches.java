package pl.skidam.automodpack_core.platforms;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import pl.skidam.automodpack_core.protocol.DownloadClient;

/** Asks one bulk platform endpoint about a whole set of hashes, in cap-sized partitions issued together on the shared network pool, a lone partition asked inline. */
final class PlatformBatches {

	/**
	 * Hashes per request. Neither bulk endpoint publishes a size cap: Modrinth's /version_files and CurseForge's
	 * /fingerprints only declare a 400 for a request they reject, so this is a tripwire far past any real pack. Packs run
	 * a few hundred mods, which stays inside one or two partitions and therefore well inside Modrinth's documented
	 * 300 requests per minute per IP. A rejected partition is logged and fails the round instead of emptying it.
	 */
	private static final int MAX_HASHES_PER_REQUEST = 256;

	private PlatformBatches() {}

	/**
	 * Every cap-sized partition of {@code items} answered through {@code round}, all asked at once. Null means the
	 * platform could not be asked: one partition that failed fails the round, because a partial answer must never read
	 * as "the platform answered and matched nothing".
	 */
	static <E, T> List<T> ask(String platform, List<E> items, Function<List<E>, List<T>> round) {
		if (items == null || items.isEmpty()) return List.of();

		List<List<E>> partitions = new ArrayList<>();
		for (int start = 0; start < items.size(); start += MAX_HASHES_PER_REQUEST) partitions.add(List.copyOf(items.subList(start, Math.min(items.size(), start + MAX_HASHES_PER_REQUEST))));
		if (partitions.size() == 1) {
			List<T> answered = askOne(platform, partitions.get(0), round);
			return answered == null ? notAsked(platform, 1, 1, items.size()) : answered;
		}

		List<T> hits = new ArrayList<>();
		int failed = 0;
		for (CompletableFuture<List<T>> asked : partitions.stream().map(partition -> CompletableFuture.supplyAsync(() -> askOne(platform, partition, round), DownloadClient.NET_EXECUTOR)).toList())
			try {
				List<T> answered = asked.join();
				if (answered == null) failed++;
				else hits.addAll(answered);
			} catch (CompletionException | CancellationException e) {
				failed++;
			}
		if (failed == 0) return hits;
		return notAsked(platform, failed, partitions.size(), items.size());
	}

	/** One partition on this thread; a body that throws instead of answering is a platform that could not be asked, never an empty answer. */
	private static <E, T> List<T> askOne(String platform, List<E> partition, Function<List<E>, List<T>> round) {
		try {
			return round.apply(partition);
		} catch (RuntimeException e) {
			LOGGER.warn("{} could not answer {} of them", platform, partition.size(), e);
			return null;
		}
	}

	private static <T> List<T> notAsked(String platform, int failed, int batches, int hashes) {
		LOGGER.warn("{} could not answer {} of {} batches over {} hashes; the round counts as not asked", platform, failed, batches, hashes);
		return null;
	}
}
