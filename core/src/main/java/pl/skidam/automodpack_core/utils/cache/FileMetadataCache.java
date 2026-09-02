package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

	private static final Map<Path, FileMetadataCache> INSTANCES = new HashMap<>();
	private static final Object GLOBAL_LOCK = new Object();
	private static final String RECORD_SUFFIX = ".json";

	private final Path recordsDirectory;
	private final Map<String, CachedFile> hotRecords = new HashMap<>();
	private final AtomicInteger refCount = new AtomicInteger(1);
	private final Object[] locks = new Object[64];

	public static final class CachedFile {
		private String path;
		private String contentHash;
		private long lastModifiedNanos;
		private long creationTimeNanos;
		private long size;
		private String fileKey;
		private long validatedAtNanos;

		public CachedFile() {}

		public CachedFile(String path, String contentHash, long lastModifiedNanos, long creationTimeNanos, long size, String fileKey, long validatedAtNanos) {
			this.path = path;
			this.contentHash = contentHash;
			this.lastModifiedNanos = lastModifiedNanos;
			this.creationTimeNanos = creationTimeNanos;
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

	private record FileFingerprint(long lastModifiedNanos, long creationTimeNanos, long size, String fileKey) {}

	private record ComputedHash(String hash, BasicFileAttributes attributes) {}

	public static FileMetadataCache open(Path path) throws IOException {
		Path absPath = path.toAbsolutePath().normalize();
		Files.createDirectories(absPath);
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

	private FileMetadataCache(Path recordsDirectory) {
		this.recordsDirectory = recordsDirectory;
		for (int i = 0; i < locks.length; i++) locks[i] = new Object();
	}

	public String getOrComputeHash(Path file) throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
		return getOrComputeHashWithAttributes(file, attrs);
	}

	public String getOrComputeHashWithAttributes(Path file, BasicFileAttributes attrs) {
		Path absPath = file.toAbsolutePath().normalize();
		String pathKey = absPath.toString();
		FileFingerprint fingerprint = fingerprint(attrs);
		int lockIndex = Math.floorMod(pathKey.hashCode(), locks.length);

		synchronized (locks[lockIndex]) {
			CachedFile cached = readRecord(pathKey);
			if (isCacheValid(cached, fingerprint)) return cached.contentHash();

			ComputedHash computed = computeStableHash(absPath, attrs);
			if (computed == null) return null;

			FileFingerprint stableFingerprint = fingerprint(computed.attributes());
			CachedFile newRecord = new CachedFile(pathKey, computed.hash(), stableFingerprint.lastModifiedNanos(), stableFingerprint.creationTimeNanos(), stableFingerprint.size(),
					stableFingerprint.fileKey(), validationTimeNanos());
			writeRecord(newRecord);
			return computed.hash();
		}
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

	private static boolean isCacheValid(CachedFile cached, FileFingerprint fingerprint) {
		return cached != null && cached.contentHash() != null && cached.size() == fingerprint.size() && cached.lastModifiedNanos() == fingerprint.lastModifiedNanos()
				&& cached.creationTimeNanos() == fingerprint.creationTimeNanos() && cached.fileKey() != null && cached.fileKey().equals(fingerprint.fileKey())
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
		FileFingerprint fingerprint = fingerprint(attrs);
		CachedFile record = new CachedFile(absPath.toString(), hash, fingerprint.lastModifiedNanos(), fingerprint.creationTimeNanos(), fingerprint.size(), fingerprint.fileKey(), validationTimeNanos());
		int lockIndex = Math.floorMod(record.path().hashCode(), locks.length);
		synchronized (locks[lockIndex]) {
			hotRecords.put(record.path(), record);
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
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-1 is required by the cache record layout", e);
		}
	}

	@Override
	public void close() {
		synchronized (GLOBAL_LOCK) {
			if (refCount.decrementAndGet() <= 0) {
				hotRecords.clear();
				INSTANCES.remove(recordsDirectory, this);
			}
		}
	}
}
