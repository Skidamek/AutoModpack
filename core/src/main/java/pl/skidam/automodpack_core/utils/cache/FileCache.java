package pl.skidam.automodpack_core.utils.cache;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.PlatformUtils;

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
public class FileCache extends LooseRecordCache<FileCache.CachedFile> {

	private static final SharedCacheRegistry<FileCache> REGISTRY = new SharedCacheRegistry<>();
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

	/**
	 * One stat for an identity question: every field {@link FileFingerprint} needs plus the regular-file and
	 * symlink flags the callers reject on. OpenJDK Unix filesystems (macOS included) answer it from a single
	 * fused {@code "unix:*"} read; filesystems without that view fall back to the split reads, producing
	 * identical values for an unchanged file.
	 */
	public record StatSnapshot(FileTime lastModifiedTime, FileTime creationTime, long changeTimeNanos, long size, String fileKey, boolean regularFile, boolean symbolicLink) {
		/** Whether this is the only kind the cache tracks: a regular file that is not a symbolic link. */
		public boolean isTrackedRegularFile() {
			return regularFile && !symbolicLink;
		}

		public FileFingerprint fingerprint() {
			return new FileFingerprint(toNanos(lastModifiedTime), toNanos(creationTime), changeTimeNanos, size, fileKey);
		}
	}

	private record ComputedHash(String hash, FileFingerprint fingerprint) {}

	public static FileCache open(Path path) throws IOException {
		return REGISTRY.acquire(path, FileCache::new);
	}

	private FileCache(Path recordsDirectory) {
		super(recordsDirectory, "file cache");
	}

	/** Git worktree identity. Named CAS objects use {@link #matchesImmutable}. */
	public String getOrComputeHash(Path file) throws IOException {
		return getOrComputeHashWithFingerprint(file.toAbsolutePath().normalize(), statSnapshot(file).fingerprint());
	}

	/** Full-read identity for explicit fsck. Hot paths use {@link #getOrComputeHash(Path)}. */
	public String hash(Path file) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		StatSnapshot snapshot = statSnapshot(absPath);
		if (!snapshot.isTrackedRegularFile()) throw new IOException("Cannot hash a non-regular file without following links: " + absPath);
		String pathKey = absPath.toString();
		synchronized (lock(pathKey)) {
			ComputedHash computed = computeStableHash(absPath, snapshot.fingerprint());
			if (computed == null) throw new IOException("Cannot obtain a stable hash for file: " + absPath);
			return computed.hash();
		}
	}

	private String getOrComputeHashWithFingerprint(Path file, FileFingerprint fingerprint) {
		Path absPath = file.toAbsolutePath().normalize();
		String pathKey = absPath.toString();
		synchronized (lock(pathKey)) {
			CachedFile cached = readRecord(pathKey, CachedFile.class);
			if (isCacheValid(cached, fingerprint)) return cached.contentHash();

			ComputedHash computed = computeStableHash(absPath, fingerprint);
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
			FileFingerprint fingerprint = statSnapshot(absPath).fingerprint();
			CachedFile cached = readRecord(pathKey, CachedFile.class);
			if (!statsMatch(cached, fingerprint)) {
				getOrComputeHashWithFingerprint(absPath, fingerprint);
				cached = readRecord(pathKey, CachedFile.class);
				if (!statsMatch(cached, statSnapshot(absPath).fingerprint()))
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
	 * reading content; a missing or disturbed record forces one stable full read that is published
	 * either way, so every answer — true or false — reflects bytes seen at the current fingerprint
	 * and repeated asks of unchanged disturbed bytes are answered by stat alone. The tripwire
	 * compares only what a content write changes (size, mtime, inode): our own publication
	 * ({@code link()}, {@code chmod()}) bumps inode ctime by design, and treating that as disturb
	 * forced full rehashes of multi-gigabyte objects.
	 */
	public boolean matchesImmutable(Path file, long expectedSize, String expectedSha1) throws IOException {
		if (!HashUtils.isSha1(expectedSha1)) return false;
		return matchesImmutable(file, expectedSize, expectedSha1, statSnapshot(file));
	}

	/** Snapshot-carrying variant so a caller holding one fused stat answers the whole question from it. */
	public boolean matchesImmutable(Path file, long expectedSize, String expectedSha1, StatSnapshot snapshot) {
		if (!HashUtils.isSha1(expectedSha1)) return false;
		if (!snapshot.isTrackedRegularFile() || snapshot.size() != expectedSize) return false;
		String sha1 = HashUtils.normalizeSha1(expectedSha1);
		Path absPath = file.toAbsolutePath().normalize();
		String pathKey = absPath.toString();
		synchronized (lock(pathKey)) {
			CachedFile cached = readRecord(pathKey, CachedFile.class);
			FileFingerprint fingerprint = snapshot.fingerprint();
			if (immutableStatsMatch(cached, fingerprint)) return sha1.equalsIgnoreCase(cached.contentHash());
			String observed = getOrComputeHashWithFingerprint(absPath, fingerprint);
			return observed != null && sha1.equalsIgnoreCase(observed);
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

	public static FileFingerprint fingerprint(Path path) throws IOException {
		return statSnapshot(path).fingerprint();
	}

	private static StatSnapshot snapshot(Path path, BasicFileAttributes attributes, WindowsFileStat.Snapshot nativeStat) {
		return new StatSnapshot(attributes.lastModifiedTime(), attributes.creationTime(), nativeStat != null ? nativeStat.changeTimeNanos() : unixChangeTimeNanos(path), attributes.size(),
				nativeStat != null ? nativeStat.fileKey() : keyString(attributes), attributes.isRegularFile(), attributes.isSymbolicLink());
	}

	/**
	 * A single-stat {@link StatSnapshot} of {@code path}, never following symbolic links. Windows answers it
	 * from the one native stat and OpenJDK Unix filesystems from one fused read; if any needed field comes
	 * back missing or mistyped the split reads below produce the same fingerprint.
	 */
	public static StatSnapshot statSnapshot(Path path) throws IOException {
		if (PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS) {
			// The native answers the whole question from one open; the split reads only speak for it when it cannot.
			StatSnapshot nativeStat = WindowsFileStat.statSnapshot(path);
			if (nativeStat != null) return nativeStat;
			return splitSnapshot(path);
		}
		try {
			StatSnapshot fused = fusedSnapshot(Files.readAttributes(path, "unix:*", LinkOption.NOFOLLOW_LINKS));
			if (fused != null) return fused;
		} catch (UnsupportedOperationException | IllegalArgumentException e) {
			// No fused unix view on this filesystem (foreign providers, odd mounts): the split reads answer identically.
		}
		return splitSnapshot(path);
	}

	private static StatSnapshot splitSnapshot(Path path) throws IOException {
		return snapshot(path, Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS), WindowsFileStat.read(path));
	}

	/** Parses the fused read; null whenever a field is missing or mistyped, so the caller falls back to the split reads. */
	private static StatSnapshot fusedSnapshot(Map<String, Object> attributes) {
		FileTime lastModifiedTime = fileTime(attributes, "lastModifiedTime");
		FileTime creationTime = fileTime(attributes, "creationTime");
		FileTime changeTime = fileTime(attributes, "ctime");
		if (lastModifiedTime == null || creationTime == null || changeTime == null || !(attributes.get("size") instanceof Long size) || !(attributes.get("isRegularFile") instanceof Boolean regularFile)
				|| !(attributes.get("isSymbolicLink") instanceof Boolean symbolicLink))
			return null;
		Object key = attributes.get("fileKey");
		return new StatSnapshot(lastModifiedTime, creationTime, toNanos(changeTime), size, key == null ? null : key.toString(), regularFile, symbolicLink);
	}

	private static FileTime fileTime(Map<String, Object> attributes, String name) {
		return attributes.get(name) instanceof FileTime time ? time : null;
	}

	private static String keyString(BasicFileAttributes attrs) {
		return attrs.fileKey() == null ? null : attrs.fileKey().toString();
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

	private ComputedHash computeStableHash(Path file, FileFingerprint initialFingerprint) {
		FileFingerprint beforeFingerprint = initialFingerprint;
		for (int attempt = 0; attempt < 3; attempt++) {
			String hash = HashUtils.getHash(file);
			if (hash == null) return null;
			try {
				StatSnapshot after = statSnapshot(file);
				if (!after.isTrackedRegularFile()) return null;
				FileFingerprint afterFingerprint = after.fingerprint();
				if (beforeFingerprint.equals(afterFingerprint)) return new ComputedHash(hash, afterFingerprint);
				beforeFingerprint = afterFingerprint;
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

	/** Git {@code ce_match_stat}: size, mtime, ctime, creation time, and inode/file key. An unknown key (null) matches only another unknown. */
	static boolean statsMatch(CachedFile cached, FileFingerprint fingerprint) {
		return cached != null && cached.contentHash() != null && cached.size() == fingerprint.size() && cached.lastModifiedNanos() == fingerprint.lastModifiedNanos()
				&& cached.creationTimeNanos() == fingerprint.creationTimeNanos() && cached.changeTimeNanos() == fingerprint.changeTimeNanos()
				&& Objects.equals(cached.fileKey(), fingerprint.fileKey());
	}

	/**
	 * Named-object tripwire: only what a content write changes. In-place corruption always changes mtime
	 * and usually the inode or size, while ctime also moves on {@code link()} and {@code chmod()}, which
	 * our immutable publication performs on every apply.
	 */
	static boolean immutableStatsMatch(CachedFile cached, FileFingerprint fingerprint) {
		return cached != null && cached.contentHash() != null && cached.size() == fingerprint.size() && cached.lastModifiedNanos() == fingerprint.lastModifiedNanos()
				&& Objects.equals(cached.fileKey(), fingerprint.fileKey());
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

	/** Snapshot-carrying twin of {@link #getHashOrNull(Path)}: the identity question is answered from the snapshot's single stat. */
	public String getHashOrNull(Path path, StatSnapshot snapshot) {
		return getOrComputeHashWithFingerprint(path.toAbsolutePath().normalize(), snapshot.fingerprint());
	}

	// Use only if you are SURE of the file state!
	public void overwriteCache(Path file, String hash) throws IOException {
		overwriteCache(file, hash, null);
	}

	public void overwriteCache(Path file, String hash, String murmur) throws IOException {
		Path absPath = file.toAbsolutePath().normalize();
		FileFingerprint fingerprint = statSnapshot(absPath).fingerprint();
		String pathKey = absPath.toString();
		synchronized (lock(pathKey)) {
			CachedFile previous = readRecord(pathKey, CachedFile.class);
			String storedMurmur = murmur != null ? murmur : previous != null && Objects.equals(previous.contentHash(), hash) ? previous.murmur() : null;
			CachedFile record = new CachedFile(pathKey, hash, fingerprint.lastModifiedNanos(), fingerprint.creationTimeNanos(), fingerprint.changeTimeNanos(), fingerprint.size(), fingerprint.fileKey(),
					validationTimeNanos(), storedMurmur);
			writeRecord(pathKey, record);
		}
	}

	@Override
	protected Path recordPath(String pathKey) {
		return super.recordPath(sha1(pathKey));
	}

	private static String sha1(String value) {
		return HashUtils.sha1(value);
	}

}
