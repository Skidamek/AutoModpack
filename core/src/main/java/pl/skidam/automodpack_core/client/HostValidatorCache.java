package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.reflect.TypeToken;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.DurableFiles;

/**
 * Persists each host's own ETag per reserved document, so foreign hosts whose ETags are not our sha1 (buckets, static
 * CDNs) can answer 304 for unchanged documents instead of re-serving them. The cache holds only validators, never
 * truth: a replayed etag can at worst produce a 304 whose content is the mirror the vouch already stood behind, and
 * every 200 is judged by the body hash regardless. Entries are keyed per endpoint because different hosts mint
 * different validators for the same document; the cap bounds the file (two documents per endpoint) and eviction is
 * insertion-ordered, so a rotation of many endpoints keeps the recent ones.
 */
public final class HostValidatorCache {
	// Two documents per endpoint; 32 entries ≈ a dozen hosts' worth of validators, a few hundred bytes of JSON.
	private static final int MAX_ENTRIES = 32;
	private static final String FILE_NAME = "host-validators.json";

	private final Path file;
	private final Map<String, String> entries;

	private HostValidatorCache(Path file, Map<String, String> entries) {
		this.file = file;
		this.entries = entries;
	}

	// One cache instance per process and storage directory: two concurrent fetches share it instead of doing
	// whole-file read-modify-write that drops each other's entries. Keyed by file, so tests on their own
	// temp storages never see each other's entries.
	private static final Map<Path, HostValidatorCache> instances = new ConcurrentHashMap<>();

	/** The process-lifetime cache for this storage; a missing or corrupt file costs only the optimization. */
	public static HostValidatorCache load(ClientStorage storage) {
		Path file = storage.clientDirectory().resolve(FILE_NAME);
		return instances.computeIfAbsent(file, HostValidatorCache::loadFromDisk);
	}

	private static HostValidatorCache loadFromDisk(Path file) {
		Map<String, String> entries = new LinkedHashMap<>();
		if (Files.isRegularFile(file)) {
			try {
				String json = Files.readString(file);
				ConfigTools.GSON.<Map<String, String>>fromJson(json, new TypeToken<LinkedHashMap<String, String>>() {
				}.getType())
						.forEach(entries::put);
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Cannot read the host validator cache {}; conditionals fall back to sha1-only", file.getFileName(), e);
			}
		}
		return new HostValidatorCache(file, entries);
	}

	/** The cached raw ETag for one document on one endpoint, null when none; the value includes its quotes and replays verbatim. */
	public synchronized String get(InetSocketAddress endpoint, String documentKey) {
		return entries.get(key(endpoint, documentKey));
	}

	/** Caches the raw ETag a 200 answer served for one document; null removes a stale entry (the host stopped sending one). */
	public synchronized void put(InetSocketAddress endpoint, String documentKey, String etag) {
		String key = key(endpoint, documentKey);
		if (etag == null) {
			if (entries.remove(key) == null) return;
		} else {
			if (etag.equals(entries.get(key))) return;
			entries.remove(key); // re-insertion order: the most recently validated endpoint evicts last
			entries.put(key, etag);
			while (entries.size() > MAX_ENTRIES) {
				String eldest = entries.keySet().iterator().next();
				entries.remove(eldest);
			}
		}
		try {
			Files.createDirectories(file.getParent());
			DurableFiles.writeAtomic(file, ConfigTools.GSON.toJson(entries).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			// A cache that cannot persist costs only the optimization: the next sync fetches unconditionally.
			LOGGER.warn("Cannot persist the host validator cache {}", file.getFileName(), e);
		}
	}

	private static String key(InetSocketAddress endpoint, String documentKey) {
		return AddressHelpers.formatAddress(endpoint) + " " + documentKey;
	}
}
