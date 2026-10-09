package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import pl.skidam.automodpack_core.config.ConfigTools;

/**
 * Shared skeleton for caches of immutable loose records: a 64-slot striped lock array, a hot-record
 * map, two-character-sharded record paths, and refcounted open/close through {@link SharedCacheRegistry}.
 * Subclasses own the domain record and answer two separate questions about it: {@link #belongsTo} whether
 * it is the record this key addresses, and {@link #isCurrent} whether its shape still carries everything a
 * reader needs. The split matters because the two failures are not the same event - content filed under the
 * wrong key is corruption and is set aside with a loud log, while a record from before a field existed is a
 * rebuildable cache miss that is quietly overwritten.
 */
public abstract class LooseRecordCache<T> implements AutoCloseable {
	protected static final String RECORD_SUFFIX = ".json";
	protected final Path recordsDirectory;
	private final String description;
	/* Readers and writers hold different per-key locks, so the map itself must be concurrent. */
	protected final Map<String, T> hotRecords;
	private final Object[] locks = new Object[64];

	/*
	 * Warm records survive instance close: every open site is try-with-resources, so a per-instance map is wiped at
	 * each refcount-0 boundary and the next pass re-reads every record from disk - thousands of loads per join. The
	 * map is keyed per records directory (one record type per directory), bounded by the keys a process touches, and
	 * every read still tripwire-validates, so warmth is exactly as trustworthy as the disk copy.
	 */
	private static final Map<Path, Map<String, Object>> WARM_RECORDS = new ConcurrentHashMap<>();

	public LooseRecordCache(Path recordsDirectory, String description) {
		this.recordsDirectory = recordsDirectory;
		this.description = description;
		@SuppressWarnings("unchecked")
		Map<String, T> warm = (Map<String, T>) WARM_RECORDS.computeIfAbsent(recordsDirectory.toAbsolutePath().normalize(), ignored -> new ConcurrentHashMap<>());
		this.hotRecords = warm;
		for (int i = 0; i < locks.length; i++) locks[i] = new Object();
	}

	protected Object lock(String key) {
		return locks[Math.floorMod(key.hashCode(), locks.length)];
	}

	protected Path recordPath(String key) {
		return recordsDirectory.resolve(key.substring(0, 2)).resolve(key.substring(2) + RECORD_SUFFIX);
	}

	/**
	 * The record for {@code key}, or null when none is persisted or the one persisted cannot answer. Content filed
	 * under the wrong key is set aside as evidence and reads as absent; physical IO trouble cannot read as a miss and
	 * escapes as {@link UncheckedIOException}.
	 */
	protected T readRecord(String key, Class<T> type) {
		T hot = hotRecords.get(key);
		if (hot != null && belongsTo(hot, key) && isCurrent(hot)) return hot;
		if (hot != null) hotRecords.remove(key, hot);
		try {
			T record = ConfigTools.readState(recordPath(key), type, description + " cache record", fields -> {
				if (!belongsTo(fields, key)) throw new IllegalArgumentException("Cache record does not belong to " + key);
				return fields;
			}).orElse(null);
			// A record that belongs here but no longer answers is rebuilt, not set aside: it is the expected cost of
			// every shape change, and the rebuild overwrites it in place.
			if (record == null || !isCurrent(record)) return null;
			hotRecords.put(key, record);
			return record;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	protected void writeRecord(String key, T record) {
		hotRecords.put(key, record);
		try {
			// A cache record is rebuildable: a bulk hash run publishes thousands of records, so
			// publication stays atomic-rename but skips the fsync pair, and a record lost to a power
			// cut only costs one rehash.
			ConfigTools.writeCached(recordPath(key), record);
		} catch (IOException e) {
			LOGGER.debug("Could not persist {} cache record: {}", description, key, e);
		}
	}

	/** Whether {@code record} is the record this {@code key} addresses; guards hot hits and disk reads alike. */
	protected abstract boolean belongsTo(T record, String key);

	/** Whether {@code record} still carries everything a reader needs; a record that does not is rebuilt rather than answered. */
	protected boolean isCurrent(T record) {
		return true;
	}

	/** Releases this instance from its subclass registry; true when the last reference closed. */
	protected abstract boolean releaseFromRegistry();

	@Override
	public void close() {
		// Warm records stay: the registry hands the next opener a fresh instance over the same shared map.
		releaseFromRegistry();
	}
}
