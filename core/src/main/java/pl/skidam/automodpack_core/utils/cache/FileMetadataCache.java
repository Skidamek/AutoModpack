package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

public class FileMetadataCache implements AutoCloseable {

	private static final Map<Path, FileMetadataCache> INSTANCES = new HashMap<>();
	private static final Object GLOBAL_LOCK = new Object();

	private final Path dbPath;
	private final MVStore store;
	private final MVMap<String, CachedFile> fileMetadataMap;
	private final AtomicInteger refCount = new AtomicInteger(1);

	private final Object[] locks = new Object[64];

	public record CachedFile(String contentHash, long lastModifiedNanos, long creationTimeNanos, long size, String fileKey, long validatedAtNanos) implements Serializable {
		@java.io.Serial
		private static final long serialVersionUID = 1L;
	}

	private record FileFingerprint(long lastModifiedNanos, long creationTimeNanos, long size, String fileKey) {}

	private record ComputedHash(String hash, BasicFileAttributes attributes) {}

	public static FileMetadataCache open(Path path) {
		Path absPath = path.toAbsolutePath().normalize();
		SmartFileUtils.createParentDirsNoEx(absPath);
		synchronized (GLOBAL_LOCK) {
			FileMetadataCache existing = INSTANCES.get(absPath);
			if (existing != null) {
				existing.refCount.incrementAndGet();
				return existing;
			}

			FileMetadataCache newCache = new FileMetadataCache(absPath);
			INSTANCES.put(absPath, newCache);
			return newCache;
		}
	}

	private FileMetadataCache(Path dbPath) {
		this.dbPath = dbPath;
		this.store = new MVStore.Builder().fileName(dbPath.toString()).cacheSize(20).open();

		this.fileMetadataMap = store.openMap("file_metadata_v2");

		for (int i = 0; i < locks.length; i++) {
			locks[i] = new Object();
		}
	}

	public String getOrComputeHash(Path file) throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
		return getOrComputeHashWithAttributes(file, attrs);
	}

	public String getOrComputeHashWithAttributes(Path file, BasicFileAttributes attrs) {
		Path absPath = file.toAbsolutePath().normalize();
		String pathKey = absPath.toString();
		FileFingerprint fingerprint = fingerprint(attrs);

		CachedFile cached = fileMetadataMap.get(pathKey);
		if (isCacheValid(cached, fingerprint)) {
			return cached.contentHash(); // CACHE HIT
		}

		// Calculate which lock bucket to use
		int lockIndex = Math.floorMod(pathKey.hashCode(), locks.length);

		synchronized (locks[lockIndex]) {
			// Check if another thread has already updated the cache
			cached = fileMetadataMap.get(pathKey);
			if (isCacheValid(cached, fingerprint)) return cached.contentHash();

			ComputedHash computed = computeStableHash(absPath, attrs);
			if (computed == null) return null;

			FileFingerprint stableFingerprint = fingerprint(computed.attributes());
			CachedFile newRecord = new CachedFile(computed.hash(), stableFingerprint.lastModifiedNanos(), stableFingerprint.creationTimeNanos(), stableFingerprint.size(),
					stableFingerprint.fileKey(), validationTimeNanos());
			fileMetadataMap.put(pathKey, newRecord);

			return computed.hash();
		}
	}

	private static FileFingerprint fingerprint(BasicFileAttributes attrs) {
		String fileKey = attrs.fileKey() == null ? "null" : attrs.fileKey().toString();
		return new FileFingerprint(toNanos(attrs.lastModifiedTime()), toNanos(attrs.creationTime()), attrs.size(), fileKey);
	}

	private static long toNanos(FileTime time) {
		return time.to(TimeUnit.NANOSECONDS);
	}

	private static long validationTimeNanos() {
		Instant now = Instant.now();
		return now.getEpochSecond() * 1_000_000_000L + now.getNano();
	}

	private static boolean sameFingerprint(BasicFileAttributes first, BasicFileAttributes second) {
		return fingerprint(first).equals(fingerprint(second));
	}

	private ComputedHash computeStableHash(Path file, BasicFileAttributes initialAttributes) {
		BasicFileAttributes before = initialAttributes;
		for (int attempt = 0; attempt < 3; attempt++) {
			String hash = HashUtils.getHash(file);
			if (hash == null) return null;
			try {
				BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
				if (sameFingerprint(before, after)) return new ComputedHash(hash, after);
				before = after;
			} catch (IOException e) {
				return null;
			}
		}
		LOGGER.warn("File changed while hashing; refusing to cache an unstable hash: {}", file);
		return null;
	}

	private boolean isCacheValid(CachedFile cached, FileFingerprint fingerprint) {
		return cached != null && cached.contentHash() != null && cached.size() == fingerprint.size() && cached.lastModifiedNanos() == fingerprint.lastModifiedNanos()
				&& cached.creationTimeNanos() == fingerprint.creationTimeNanos() && cached.fileKey().equals(fingerprint.fileKey())
				&& fingerprint.lastModifiedNanos() < cached.validatedAtNanos();
	}

	public String getHashOrNull(Path path) {
		try {
			return getOrComputeHash(path);
		} catch (IOException e) {
			LOGGER.error("Failed to compute hash for path: {}", path, e);
			return null;
		}
	}

	public String getHashOrNullWithAttributes(Path path, BasicFileAttributes attrs) {
		try {
			return getOrComputeHashWithAttributes(path, attrs);
		} catch (Exception e) {
			LOGGER.error("Failed to compute hash for path: {}", path, e);
			return null;
		}
	}

	public boolean fastHashCompare(Path file1, Path file2) throws IOException {
		if (!Files.exists(file1) || !Files.exists(file2)) return false;

		String hash1 = getOrComputeHash(file1);
		String hash2 = getOrComputeHash(file2);

		if (hash1 == null || hash2 == null) return false;

		return hash1.equals(hash2);
	}

	// Use only if you are SURE of the file state!
	public void overwriteCache(Path file, String hash) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		String pathKey = absPath.toString();

		BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class);
		FileFingerprint fingerprint = fingerprint(attrs);
		CachedFile newRecord = new CachedFile(hash, fingerprint.lastModifiedNanos(), fingerprint.creationTimeNanos(), fingerprint.size(), fingerprint.fileKey(), validationTimeNanos());
		fileMetadataMap.put(pathKey, newRecord);
	}

	public void cleanup() {
		synchronized (store) {
			fileMetadataMap.keySet().removeIf(pathString -> Files.notExists(Path.of(pathString)));
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
