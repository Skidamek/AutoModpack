package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The sources one update transaction has already proved. A plan names the same content-addressed object through
 * validation, the destination check and the link itself; without this record each of those asks pays its own stat and
 * its own cache-record lookup for bytes that cannot change under the transaction's lock. One record is kept per SHA-1
 * for the life of one transaction, and every reuse is answered only after the source's own stat still names the record's
 * fingerprint (size, mtime, creation time, change time and inode), so a rewritten, replaced or unsealed source is
 * re-validated instead of trusted. There is deliberately no process-wide cache: the answer belongs to the transaction
 * that proved it.
 */
public final class ValidatedSources {
	/**
	 * One source as it was proved: the exact path and size the plan named, the fingerprint seen when the bytes were
	 * verified, and whether the read-only policy was applied then. A matching fingerprint means ctime and inode are
	 * also unchanged, so a sealed source cannot have lost its seal without invalidating the record.
	 */
	public record Source(Path path, long size, FileCache.FileFingerprint fingerprint, boolean sealed) {}

	private final FileCache cache;
	private final ConcurrentMap<String, Source> validated = new ConcurrentHashMap<>();

	public ValidatedSources(FileCache cache) {
		this.cache = cache;
	}

	/** Whether the source is the advertised bytes, answered from the transaction record when its fingerprint still agrees. */
	public boolean matches(Path source, long expectedSize, String expectedSha1) {
		try {
			return require(source, expectedSize, expectedSha1) != null;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * The validated source, proving and recording it on first use. A fingerprint change re-runs the full named-object
	 * check and re-applies protection; no caller ever gets an answer about different bytes under the same name.
	 */
	public Source require(Path source, long expectedSize, String expectedSha1) throws IOException {
		if (!HashUtils.isSha1(expectedSha1)) throw new IOException("Invalid source SHA-1: " + expectedSha1);
		Path normalized = source.toAbsolutePath().normalize();
		String key = HashUtils.normalizeSha1(expectedSha1);
		FileCache.SealedSnapshot sealedSnapshot = FileCache.statSnapshotAndSealed(normalized);
		FileCache.StatSnapshot snapshot = sealedSnapshot != null ? sealedSnapshot.snapshot() : FileCache.statSnapshot(normalized);
		Source known = validated.get(key);
		if (known != null && known.size() == expectedSize && known.path().equals(normalized) && known.fingerprint().equals(snapshot.fingerprint()) && snapshot.isTrackedRegularFile())
			return known;
		boolean matches = cache == null
				? FileIntegrity.matches(normalized, expectedSize, expectedSha1, null, snapshot)
				: FileIntegrity.matchesNamed(normalized, expectedSize, expectedSha1, cache, snapshot);
		if (!snapshot.isTrackedRegularFile() || snapshot.size() != expectedSize || !matches)
			throw new IOException("Source file failed size/SHA-1 verification: " + normalized);
		// The first proof also applies the read-only policy, so reuse needs no second protection pass: the fingerprint
		// proven here carries ctime, and any chmod after it invalidates the record before a link can trust it. The one
		// stat above already answers whether the policy is present; only an unsealed source pays the separate pass.
		boolean sealed = sealedSnapshot != null && sealedSnapshot.sealed();
		if (!sealed) {
			ImmutableFiles.protect(normalized);
			// Protection changes ctime on the inode; record the post-protection fingerprint so the next reuse matches.
			snapshot = FileCache.statSnapshot(normalized);
			sealed = true;
		}
		Source record = new Source(normalized, expectedSize, snapshot.fingerprint(), sealed);
		validated.put(key, record);
		return record;
	}
}
