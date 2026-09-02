package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * A shared, path-keyed file hash cache backed by immutable loose records.
 *
 * <p>
 * Mutable worktree reuse follows Git: {@code ce_match_stat} (size, mtime, ctime, inode) plus
 * {@code is_racy_timestamp} (mtime not older than the record → rehash). Named immutable objects
 * (CAS store) use a narrower tripwire of only size, mtime, and inode, because {@code link()} and
 * {@code chmod()} bump inode ctime on every publication and ctime-only disturb would force full
 * rehashes of multi-gigabyte objects.
 * </p>
 *
 * <p>
 * Each record is published with an atomic rename. This keeps the cache safe
 * when a client and a logical server use the same data root, without keeping a
 * process-wide database open or requiring a database dependency.
 * </p>
 */
public class FileMetadataCache extends LooseRecordCache<FileMetadataCache.CachedFile> {

	private static final SharedCacheRegistry<FileMetadataCache> REGISTRY = new SharedCacheRegistry<>();
	private static final long UNAVAILABLE_CHANGE_TIME_NANOS = Long.MIN_VALUE;

	public static final class CachedFile {
		private String path;
		private String contentHash;
		private String murmur;
		private long lastModifiedNanos;
		private long creationTimeNanos;
		private long changeTimeNanos;
		private long size;
		private String fileKey;
		private long validatedAtNanos;

		public CachedFile() {}

		public CachedFile(String path, String contentHash, long lastModifiedNanos, long creationTimeNanos, long changeTimeNanos, long size, String fileKey, long validatedAtNanos) {
			this(path, contentHash, lastModifiedNanos, creationTimeNanos, changeTimeNanos, size, fileKey, validatedAtNanos, null);
		}

		public CachedFile(String path, String contentHash, long lastModifiedNanos, long creationTimeNanos, long changeTimeNanos, long size, String fileKey, long validatedAtNanos, String murmur) {
			this.path = path;
			this.contentHash = contentHash;
			this.murmur = murmur;
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

		public String murmur() {
			return murmur;
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

	public record FileFingerprint(long lastModifiedNanos, long creationTimeNanos, long changeTimeNanos, long size, String fileKey) {}

	private record ComputedHash(String hash, BasicFileAttributes attributes, FileFingerprint fingerprint) {}

	public static FileMetadataCache open(Path path) throws IOException {
		return REGISTRY.acquire(path, FileMetadataCache::new);
	}

	private FileMetadataCache(Path recordsDirectory) {
		super(recordsDirectory, "file metadata");
	}

	/** Applies a Git-style persisted stat cache; explicit integrity repair uses rehash(). */
	public String getOrComputeHash(Path file) throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
		return getOrComputeHashWithAttributes(file, attrs);
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
		synchronized (lock(pathKey)) {
			ComputedHash computed = computeStableHash(absPath, attrs, LinkOption.NOFOLLOW_LINKS);
			if (computed == null) throw new IOException("Cannot obtain a stable hash for file: " + absPath);
			return publishComputed(pathKey, computed);
		}
	}

	/** Full-read identity for explicit fsck. Hot paths use {@link #getOrComputeHash(Path)}. */
	public String hash(Path file) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("Cannot hash a non-regular file without following links: " + absPath);
		String pathKey = absPath.toString();
		synchronized (lock(pathKey)) {
			ComputedHash computed = computeStableHash(absPath, attrs, LinkOption.NOFOLLOW_LINKS);
			if (computed == null) throw new IOException("Cannot obtain a stable hash for file: " + absPath);
			return computed.hash();
		}
	}

	public String getOrComputeHashWithAttributes(Path file, BasicFileAttributes attrs) {
		Path absPath = file.toAbsolutePath().normalize();
		String pathKey = absPath.toString();
		FileFingerprint fingerprint = fingerprint(absPath, attrs);
		synchronized (lock(pathKey)) {
			CachedFile cached = readRecord(pathKey, CachedFile.class);
			if (isCacheValid(cached, fingerprint)) return cached.contentHash();

			ComputedHash computed = computeStableHash(absPath, attrs);
			if (computed == null) return null;

			return publishComputed(pathKey, computed);
		}
	}

	private String publishComputed(String pathKey, ComputedHash computed) {
		FileFingerprint stableFingerprint = computed.fingerprint();
		CachedFile previous = hotRecords.get(pathKey);
		String murmur = previous != null && Objects.equals(previous.contentHash(), computed.hash()) ? previous.murmur() : null;
		CachedFile record = new CachedFile(pathKey, computed.hash(), stableFingerprint.lastModifiedNanos(), stableFingerprint.creationTimeNanos(), stableFingerprint.changeTimeNanos(), stableFingerprint.size(),
				stableFingerprint.fileKey(), validationTimeNanos(), murmur);
		if (previous != null && Objects.equals(previous.contentHash(), record.contentHash()) && previous.lastModifiedNanos() == record.lastModifiedNanos()
				&& previous.creationTimeNanos() == record.creationTimeNanos() && previous.changeTimeNanos() == record.changeTimeNanos() && previous.size() == record.size()
				&& Objects.equals(previous.fileKey(), record.fileKey()) && Objects.equals(previous.murmur(), record.murmur())) {
			hotRecords.put(pathKey, record);
			return computed.hash();
		}
		writeRecord(pathKey, record);
		return computed.hash();
	}

	/**
	 * CurseForge murmur of stable bytes. Requires a valid SHA-1 record for {@code file}; computes and
	 * stores murmur only when that record has none.
	 */
	public String getOrComputeMurmur(Path file) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		String pathKey = absPath.toString();
		synchronized (lock(pathKey)) {
			BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			FileFingerprint fingerprint = fingerprint(absPath, attrs);
			CachedFile cached = readRecord(pathKey, CachedFile.class);
			if (!statsMatch(cached, fingerprint)) {
				getOrComputeHashWithAttributes(absPath, attrs);
				cached = readRecord(pathKey, CachedFile.class);
				if (!statsMatch(cached, fingerprint(absPath, Files.readAttributes(absPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))))
					throw new IOException("Cannot obtain a stable hash record for murmur: " + absPath);
			}
			if (cached.murmur() != null) return cached.murmur();
			String murmur = HashUtils.getCurseforgeMurmurHash(absPath);
			if (murmur == null) throw new IOException("CurseForge murmur calculation returned null: " + absPath);
			CachedFile updated = new CachedFile(cached.path(), cached.contentHash(), cached.lastModifiedNanos(), cached.creationTimeNanos(), cached.changeTimeNanos(), cached.size(), cached.fileKey(),
					cached.validatedAtNanos(), murmur);
			writeRecord(pathKey, updated);
			return murmur;
		}
	}

	/**
	 * Whether {@code file} is still the named immutable bytes. A matching record is trusted without
	 * reading content. A missing or disturbed record forces one full read before the tripwire is
	 * seeded or refreshed, so a true answer always means the bytes were seen at least once. The
	 * tripwire compares only what a content write changes (size, mtime, inode): our own publication
	 * ({@code link()}, {@code chmod()}) bumps inode ctime by design, and treating that as disturb
	 * forced full rehashes of multi-gigabyte objects.
	 */
	public boolean matchesImmutable(Path file, long expectedSize, String expectedSha1) throws IOException {
		if (!HashUtils.isSha1(expectedSha1)) return false;
		Path absPath = file.toAbsolutePath().normalize();
		if (!Files.isRegularFile(absPath, LinkOption.NOFOLLOW_LINKS)) return false;
		BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (attrs.isSymbolicLink() || attrs.size() != expectedSize) return false;
		String sha1 = HashUtils.normalizeSha1(expectedSha1);
		String pathKey = absPath.toString();
		synchronized (lock(pathKey)) {
			FileFingerprint fingerprint = fingerprint(absPath, attrs);
			CachedFile cached = readRecord(pathKey, CachedFile.class);
			if (immutableStatsMatch(cached, fingerprint)) return sha1.equalsIgnoreCase(cached.contentHash());
			String actual = HashUtils.getHash(absPath);
			if (actual == null || !sha1.equalsIgnoreCase(actual)) return false;
			writeRecord(pathKey, new CachedFile(pathKey, sha1, fingerprint.lastModifiedNanos(), fingerprint.creationTimeNanos(), fingerprint.changeTimeNanos(), fingerprint.size(), fingerprint.fileKey(),
					validationTimeNanos(), cached == null ? null : cached.murmur()));
			return true;
		}
	}

	@Override
	protected boolean validate(CachedFile record, String key) {
		return key.equals(record.path());
	}

	@Override
	protected boolean releaseFromRegistry() {
		return REGISTRY.release(recordsDirectory, this);
	}

	public static FileFingerprint fingerprint(Path path, BasicFileAttributes attrs) {
		WindowsFileStat.Snapshot nativeStat = WindowsFileStat.read(path);
		long changeTimeNanos = nativeStat != null ? nativeStat.changeTimeNanos() : unixChangeTimeNanos(path);
		String fileKey = nativeStat != null ? nativeStat.fileKey() : attrs.fileKey() == null ? "null" : attrs.fileKey().toString();
		return new FileFingerprint(toNanos(attrs.lastModifiedTime()), toNanos(attrs.creationTime()), changeTimeNanos, attrs.size(), fileKey);
	}

	private static long unixChangeTimeNanos(Path path) {
		try {
			Object value = Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
			return value instanceof FileTime time ? toNanos(time) : UNAVAILABLE_CHANGE_TIME_NANOS;
		} catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
			return UNAVAILABLE_CHANGE_TIME_NANOS;
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

	static boolean isCacheValid(CachedFile cached, FileFingerprint fingerprint) {
		return statsMatch(cached, fingerprint) && !isRacyTimestamp(cached, fingerprint);
	}

	/** Git {@code ce_match_stat}: size, mtime, ctime, creation time, and inode/file key. */
	static boolean statsMatch(CachedFile cached, FileFingerprint fingerprint) {
		return cached != null && cached.contentHash() != null && cached.size() == fingerprint.size() && cached.lastModifiedNanos() == fingerprint.lastModifiedNanos()
				&& cached.creationTimeNanos() == fingerprint.creationTimeNanos() && cached.changeTimeNanos() == fingerprint.changeTimeNanos()
				&& cached.fileKey() != null && cached.fileKey().equals(fingerprint.fileKey());
	}

	/**
	 * Named-object tripwire: only what a content write changes. In-place corruption always changes mtime
	 * and usually the inode or size, while ctime also moves on {@code link()} and {@code chmod()}, which
	 * our immutable publication performs on every apply.
	 */
	static boolean immutableStatsMatch(CachedFile cached, FileFingerprint fingerprint) {
		return cached != null && cached.contentHash() != null && cached.size() == fingerprint.size() && cached.lastModifiedNanos() == fingerprint.lastModifiedNanos()
				&& cached.fileKey() != null && cached.fileKey().equals(fingerprint.fileKey());
	}

	/**
	 * Git {@code is_racy_timestamp}: the file's mtime is not strictly older than when the cache
	 * record was written. Worktree identity then rehashes. A racily-clean stat is not tamper.
	 */
	private static boolean isRacyTimestamp(CachedFile cached, FileFingerprint fingerprint) {
		return fingerprint.lastModifiedNanos() >= cached.validatedAtNanos();
	}

	public String getHashOrNull(Path path) {
		try {
			return getOrComputeHash(path);
		} catch (IOException e) {
			LOGGER.error("Failed to compute hash for path: {}", path, e);
			return null;
		}
	}

	// Use only if you are SURE of the file state!
	public void overwriteCache(Path file, String hash) throws IOException {
		overwriteCache(file, hash, null);
	}

	public void overwriteCache(Path file, String hash, String murmur) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		FileFingerprint fingerprint = fingerprint(absPath, attrs);
		String pathKey = absPath.toString();
		synchronized (lock(pathKey)) {
			CachedFile previous = readRecord(pathKey, CachedFile.class);
			String storedMurmur = murmur != null ? murmur : previous != null && Objects.equals(previous.contentHash(), hash) ? previous.murmur() : null;
			CachedFile record = new CachedFile(pathKey, hash, fingerprint.lastModifiedNanos(), fingerprint.creationTimeNanos(), fingerprint.changeTimeNanos(), fingerprint.size(), fingerprint.fileKey(),
					validationTimeNanos(), storedMurmur);
			writeRecord(pathKey, record);
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

	@Override
	protected Path recordPath(String pathKey) {
		return super.recordPath(sha1(pathKey));
	}

	private static String sha1(String value) {
		return HashUtils.sha1(value);
	}

}
