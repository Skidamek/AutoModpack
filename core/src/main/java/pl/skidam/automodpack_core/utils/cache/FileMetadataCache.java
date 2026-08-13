package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * A shared, path-keyed file hash cache backed by immutable loose records.
 *
 * <p>
 * Each record is published with an atomic rename. This keeps the cache safe
 * when a client and a logical server use the same data root, without keeping a
 * process-wide database open or requiring a database dependency.
 * </p>
 */
public class FileMetadataCache implements AutoCloseable {

	private static final SharedCacheRegistry<FileMetadataCache> REGISTRY = new SharedCacheRegistry<>();
	private static final String RECORD_SUFFIX = ".json";

	private final Path recordsDirectory;
	private final Map<String, CachedFile> hotRecords = new HashMap<>();
	private final Set<String> directlyHashedThisSession = new HashSet<>();
	private final Object[] locks = new Object[64];

	public static final class CachedFile {
		private String path;
		private String contentHash;
		private long lastModifiedNanos;
		private long creationTimeNanos;
		private long changeTimeNanos;
		private long size;
		private String fileKey;
		private long validatedAtNanos;

		public CachedFile() {}

		public CachedFile(String path, String contentHash, long lastModifiedNanos, long creationTimeNanos, long changeTimeNanos, long size, String fileKey, long validatedAtNanos) {
			this.path = path;
			this.contentHash = contentHash;
			this.lastModifiedNanos = lastModifiedNanos;
			this.creationTimeNanos = creationTimeNanos;
			this.changeTimeNanos = changeTimeNanos;
			this.size = size;
			this.fileKey = fileKey;
			this.validatedAtNanos = validatedAtNanos;
		}

		public String path() {
			return path;
		}

		public String contentHash() {
			return contentHash;
		}

		public long lastModifiedNanos() {
			return lastModifiedNanos;
		}

		public long creationTimeNanos() {
			return creationTimeNanos;
		}

		public long changeTimeNanos() {
			return changeTimeNanos;
		}

		public long size() {
			return size;
		}

		public String fileKey() {
			return fileKey;
		}

		public long validatedAtNanos() {
			return validatedAtNanos;
		}
	}

	private record FileFingerprint(long lastModifiedNanos, long creationTimeNanos, long changeTimeNanos, long size, String fileKey) {}

	private record ComputedHash(String hash, BasicFileAttributes attributes, FileFingerprint fingerprint) {}

	public static FileMetadataCache open(Path path) throws IOException {
		return REGISTRY.acquire(path, FileMetadataCache::new);
	}

	private FileMetadataCache(Path recordsDirectory) {
		this.recordsDirectory = recordsDirectory;
		for (int i = 0; i < locks.length; i++) locks[i] = new Object();
	}

	public String getOrComputeHash(Path file) throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
		return getOrComputeHashWithAttributes(file, attrs);
	}

	/**
	 * Returns a hash that was computed from bytes in this cache session. Persisted metadata
	 * remains a performance hint and is never authoritative for publication or deletion.
	 */
	public String getTrustedHash(Path file) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		return getTrustedHashWithAttributes(absPath, attrs);
	}

	public String getTrustedHashWithAttributes(Path file, BasicFileAttributes attrs) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		String pathKey = absPath.toString();
		FileFingerprint fingerprint = fingerprint(absPath, attrs);
		int lockIndex = Math.floorMod(pathKey.hashCode(), locks.length);
		synchronized (locks[lockIndex]) {
			CachedFile cached = readRecord(pathKey);
			if (directlyHashedThisSession.contains(pathKey) && isCacheValid(cached, fingerprint)) return cached.contentHash();
			ComputedHash computed = computeStableHash(absPath, attrs, LinkOption.NOFOLLOW_LINKS);
			if (computed == null) throw new IOException("Cannot obtain a stable trusted hash for file: " + absPath);
			return publishComputed(pathKey, computed);
		}
	}

	/**
	 * Hashes the current bytes without consulting a cached record and publishes the stable
	 * observation back to the cache. This is intended for explicit integrity checks where
	 * the cache itself is one of the things being verified.
	 */
	public String rehash(Path file) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("Cannot hash a non-regular file without following links: " + absPath);
		String pathKey = absPath.toString();
		int lockIndex = Math.floorMod(pathKey.hashCode(), locks.length);

		synchronized (locks[lockIndex]) {
			ComputedHash computed = computeStableHash(absPath, attrs, LinkOption.NOFOLLOW_LINKS);
			if (computed == null) throw new IOException("Cannot obtain a stable hash for file: " + absPath);
			return publishComputed(pathKey, computed);
		}
	}

	public String getOrComputeHashWithAttributes(Path file, BasicFileAttributes attrs) {
		Path absPath = file.toAbsolutePath().normalize();
		String pathKey = absPath.toString();
		FileFingerprint fingerprint = fingerprint(absPath, attrs);
		int lockIndex = Math.floorMod(pathKey.hashCode(), locks.length);

		synchronized (locks[lockIndex]) {
			CachedFile cached = readRecord(pathKey);
			if (isCacheValid(cached, fingerprint)) return cached.contentHash();

			ComputedHash computed = computeStableHash(absPath, attrs);
			if (computed == null) return null;

			return publishComputed(pathKey, computed);
		}
	}

	private String publishComputed(String pathKey, ComputedHash computed) {
		FileFingerprint stableFingerprint = computed.fingerprint();
		CachedFile record = new CachedFile(pathKey, computed.hash(), stableFingerprint.lastModifiedNanos(), stableFingerprint.creationTimeNanos(), stableFingerprint.changeTimeNanos(), stableFingerprint.size(),
				stableFingerprint.fileKey(), validationTimeNanos());
		writeRecord(record);
		directlyHashedThisSession.add(pathKey);
		return computed.hash();
	}

	private CachedFile readRecord(String pathKey) {
		CachedFile hot = hotRecords.get(pathKey);
		if (hot != null) return hot;

		Path recordPath = recordPath(pathKey);
		if (!Files.isRegularFile(recordPath, LinkOption.NOFOLLOW_LINKS)) return null;
		try {
			CachedFile record = ConfigTools.read(recordPath, CachedFile.class).orElse(null);
			if (record == null || !pathKey.equals(record.path())) return null;
			hotRecords.put(pathKey, record);
			return record;
		} catch (RuntimeException e) {
			LOGGER.debug("Ignoring invalid file metadata cache record: {}", recordPath);
			return null;
		}
	}

	private void writeRecord(CachedFile record) {
		hotRecords.put(record.path(), record);
		try {
			ConfigTools.writeAtomic(recordPath(record.path()), record);
		} catch (IOException e) {
			LOGGER.debug("Could not persist file metadata cache record: {}", record.path(), e);
		}
	}

	private static FileFingerprint fingerprint(Path path, BasicFileAttributes attrs) {
		String fileKey = attrs.fileKey() == null ? "null" : attrs.fileKey().toString();
		return new FileFingerprint(toNanos(attrs.lastModifiedTime()), toNanos(attrs.creationTime()), changeTimeNanos(path), attrs.size(), fileKey);
	}

	private static long changeTimeNanos(Path path) {
		try {
			Object value = Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
			return value instanceof FileTime time ? toNanos(time) : Long.MIN_VALUE;
		} catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
			return Long.MIN_VALUE;
		}
	}

	private static long toNanos(FileTime time) {
		return time.to(TimeUnit.NANOSECONDS);
	}

	private static long validationTimeNanos() {
		Instant now = Instant.now();
		return now.getEpochSecond() * 1_000_000_000L + now.getNano();
	}

	private ComputedHash computeStableHash(Path file, BasicFileAttributes initialAttributes, LinkOption... options) {
		BasicFileAttributes before = initialAttributes;
		for (int attempt = 0; attempt < 3; attempt++) {
			FileFingerprint beforeFingerprint = fingerprint(file, before);
			String hash = HashUtils.getHash(file);
			if (hash == null) return null;
			try {
				BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, options);
				if (!after.isRegularFile() || after.isSymbolicLink()) return null;
				FileFingerprint afterFingerprint = fingerprint(file, after);
				if (beforeFingerprint.equals(afterFingerprint)) return new ComputedHash(hash, after, afterFingerprint);
				before = after;
			} catch (IOException e) {
				return null;
			}
		}
		LOGGER.warn("File changed while hashing; refusing to cache an unstable hash: {}", file);
		return null;
	}

	private static boolean isCacheValid(CachedFile cached, FileFingerprint fingerprint) {
		return cached != null && cached.contentHash() != null && cached.size() == fingerprint.size() && cached.lastModifiedNanos() == fingerprint.lastModifiedNanos()
				&& cached.creationTimeNanos() == fingerprint.creationTimeNanos() && cached.changeTimeNanos() == fingerprint.changeTimeNanos()
				&& cached.fileKey() != null && cached.fileKey().equals(fingerprint.fileKey())
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
		return hash1 != null && hash1.equals(hash2);
	}

	// Use only if you are SURE of the file state!
	public void overwriteCache(Path file, String hash) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class);
		FileFingerprint fingerprint = fingerprint(absPath, attrs);
		CachedFile record = new CachedFile(absPath.toString(), hash, fingerprint.lastModifiedNanos(), fingerprint.creationTimeNanos(), fingerprint.changeTimeNanos(), fingerprint.size(), fingerprint.fileKey(),
				validationTimeNanos());
		int lockIndex = Math.floorMod(record.path().hashCode(), locks.length);
		synchronized (locks[lockIndex]) {
			hotRecords.put(record.path(), record);
			directlyHashedThisSession.add(record.path());
			ConfigTools.writeAtomic(recordPath(record.path()), record);
		}
	}

	public void cleanup() {
		if (!Files.isDirectory(recordsDirectory, LinkOption.NOFOLLOW_LINKS)) return;
		try (Stream<Path> paths = Files.walk(recordsDirectory)) {
			for (Path path : paths.filter(candidate -> candidate.getFileName().toString().endsWith(RECORD_SUFFIX))
					.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).toList()) {
				try {
					CachedFile record = ConfigTools.read(path, CachedFile.class).orElse(null);
					if (record == null || record.path() == null || Files.notExists(Path.of(record.path()))) Files.deleteIfExists(path);
				} catch (RuntimeException | IOException e) {
					Files.deleteIfExists(path);
				}
			}
		} catch (IOException e) {
			LOGGER.debug("Could not clean file metadata cache: {}", recordsDirectory, e);
		}
		hotRecords.entrySet().removeIf(entry -> Files.notExists(Path.of(entry.getKey())));
	}

	private Path recordPath(String pathKey) {
		String key = sha1(pathKey);
		return recordsDirectory.resolve(key.substring(0, 2)).resolve(key.substring(2) + RECORD_SUFFIX);
	}

	private static String sha1(String value) {
		return HashUtils.sha1(value);
	}

	@Override
	public void close() {
		if (REGISTRY.release(recordsDirectory, this)) {
			hotRecords.clear();
			directlyHashedThisSession.clear();
		}
	}
}
