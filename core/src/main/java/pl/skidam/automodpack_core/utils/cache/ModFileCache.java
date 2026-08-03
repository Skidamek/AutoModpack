package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

public class ModFileCache implements AutoCloseable {

	private static final Map<Path, ModFileCache> INSTANCES = new HashMap<>();
	private static final Object GLOBAL_LOCK = new Object();

	private final Path dbPath;
	private final MVStore store;
	private final MVMap<String, FileInspection.Mod> modFileMap;
	private final AtomicInteger refCount = new AtomicInteger(1);

	private final Object[] locks = new Object[64];

	public static ModFileCache open(Path path) {
		Path absPath = path.toAbsolutePath().normalize();
		SmartFileUtils.createParentDirsNoEx(absPath);
		synchronized (GLOBAL_LOCK) {
			ModFileCache existing = INSTANCES.get(absPath);
			if (existing != null) {
				existing.refCount.incrementAndGet();
				return existing;
			}

			ModFileCache newCache = new ModFileCache(absPath);
			INSTANCES.put(absPath, newCache);
			return newCache;
		}
	}

	private ModFileCache(Path dbPath) {
		this.dbPath = dbPath;
		this.store = new MVStore.Builder().fileName(dbPath.toString()).cacheSize(20).open();

		this.modFileMap = store.openMap("mod_file_by_hash");

		for (int i = 0; i < locks.length; i++) {
			locks[i] = new Object();
		}
	}

	public FileInspection.Mod getOrComputeMod(Path file, FileMetadataCache cache) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		String hash = cache.getOrComputeHash(absPath);
		if (hash == null) return null;
		FileInspection.Mod cached = modFileMap.get(hash);
		if (cached != null && hash.equalsIgnoreCase(cached.hash())) {
			return cached.at(absPath); // CACHE HIT
		}

		// Calculate which lock bucket to use
		int lockIndex = Math.floorMod(hash.hashCode(), locks.length);

		synchronized (locks[lockIndex]) {
			// Check if another thread has already updated the cache
			hash = cache.getOrComputeHash(absPath);
			if (hash == null) return null;
			cached = modFileMap.get(hash);
			if (cached != null && hash.equalsIgnoreCase(cached.hash())) {
				return cached.at(absPath); // CACHE HIT
			}

			// Actual work happens here
			FileInspection.Mod modFile = FileInspection.getMod(absPath, cache);

			if (modFile != null) modFileMap.put(hash, modFile.at(null));

			return modFile;
		}
	}

	public FileInspection.Mod getModOrNull(Path path, FileMetadataCache cache) {
		try {
			return getOrComputeMod(path, cache);
		} catch (IOException e) {
			LOGGER.error("Failed to compute hash for path: {}", path, e);
			return null;
		}
	}

	public void retainOnly(Iterable<String> retainedHashes) {
		Set<String> retained = new HashSet<>();
		for (String hash : retainedHashes) if (hash != null) retained.add(hash.toLowerCase(Locale.ROOT));
		synchronized (store) {
			modFileMap.keySet().removeIf(hash -> !retained.contains(hash));
			store.commit();
			store.compactFile(2000);
		}
	}

	@Override
	public void close() {
		synchronized (GLOBAL_LOCK) {
			if (refCount.decrementAndGet() <= 0) {
				try {
					if (!store.isClosed()) {
						store.commit();
						store.close();
					}
				} finally {
					INSTANCES.remove(this.dbPath, this);
				}
			}
		}
	}
}
