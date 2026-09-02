package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import pl.skidam.automodpack_core.config.ConfigTools;

/**
 * Shared skeleton for caches of immutable loose records: a 64-slot striped lock array, a hot-record
 * map, two-character-sharded record paths, and refcounted open/close through {@link SharedCacheRegistry}.
 * Subclasses own the domain record and decide through {@link #validate(Object, String)} whether a
 * record genuinely belongs to a key.
 */
abstract class LooseRecordCache<T> implements AutoCloseable {
	protected static final String RECORD_SUFFIX = ".json";
	protected final Path recordsDirectory;
	private final String description;
	/* Readers and writers hold different per-key locks, so the map itself must be concurrent. */
	protected final Map<String, T> hotRecords = new ConcurrentHashMap<>();
	private final Object[] locks = new Object[64];

	LooseRecordCache(Path recordsDirectory, String description) {
		this.recordsDirectory = recordsDirectory;
		this.description = description;
		for (int i = 0; i < locks.length; i++) locks[i] = new Object();
	}

	protected Object lock(String key) {
		return locks[Math.floorMod(key.hashCode(), locks.length)];
	}

	protected Path recordPath(String key) {
		return recordsDirectory.resolve(key.substring(0, 2)).resolve(key.substring(2) + RECORD_SUFFIX);
	}

	protected T readRecord(String key, Class<T> type) {
		T hot = hotRecords.get(key);
		if (hot != null && validate(hot, key)) return hot;
		Path recordPath = recordPath(key);
		if (!Files.isRegularFile(recordPath, LinkOption.NOFOLLOW_LINKS)) return null;
		try {
			T record = ConfigTools.read(recordPath, type).orElse(null);
			if (record == null || !validate(record, key)) return null;
			hotRecords.put(key, record);
			return record;
		} catch (RuntimeException e) {
			LOGGER.debug("Ignoring invalid {} cache record: {}", description, recordPath);
			return null;
		}
	}

	protected void writeRecord(String key, T record) {
		hotRecords.put(key, record);
		try {
			ConfigTools.writeAtomic(recordPath(key), record);
		} catch (IOException e) {
			LOGGER.debug("Could not persist {} cache record: {}", description, key, e);
		}
	}

	/** Whether {@code record} genuinely belongs to {@code key}; guards hot hits and disk reads alike. */
	protected abstract boolean validate(T record, String key);

	/** Releases this instance from its subclass registry; true when the last reference closed. */
	protected abstract boolean releaseFromRegistry();

	@Override
	public void close() {
		if (releaseFromRegistry()) hotRecords.clear();
	}
}
