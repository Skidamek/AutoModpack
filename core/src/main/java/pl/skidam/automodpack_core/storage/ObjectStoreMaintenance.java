package pl.skidam.automodpack_core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ImmutableFiles;

/**
 * Shared maintenance primitives for the content-addressed object store that the client and the
 * server instance both use. There is exactly one such store per data root, so GC, measurement,
 * and reference accounting must behave identically on both sides; keep them here instead of
 * re-implementing them per side.
 */
public final class ObjectStoreMaintenance {
	private ObjectStoreMaintenance() {}

	/** Counted files and bytes for one measured listing. */
	public record FileTotals(long count, long bytes) {
		public FileTotals {
			if (count < 0 || bytes < 0) throw new IllegalArgumentException("File totals cannot be negative");
		}

		public FileTotals plus(FileTotals other) throws IOException {
			return new FileTotals(addExact(count, other.count, "object store file count"), addExact(bytes, other.bytes, "object store bytes"));
		}
	}

	/** The receipt of one deletion pass over unreachable objects. */
	public record DeletionReceipt(long deletedCount, long deletedBytes) {
		public DeletionReceipt {
			if (deletedCount < 0 || deletedBytes < 0) throw new IllegalArgumentException("Deleted object values cannot be negative");
		}
	}

	/** Hash-to-size reference accounting that rejects conflicting advertised sizes. */
	public static final class ExpectedSizes {
		private final TreeMap<String, Long> sizes = new TreeMap<>();
		private final HashSet<String> required = new HashSet<>();

		/** Adds a reference whose object must exist and be valid. */
		public void require(String hash, long size, String source) throws IOException {
			add(hash, size, source);
			required.add(hash);
		}

		/** Adds a reference; size -1 means the advertised size is unknown. */
		public void optional(String hash, long size, String source) throws IOException {
			if (hash == null || hash.isBlank()) throw new IOException("Missing object reference from " + source);
			add(hash, size, source);
		}

		/** Adds a reference, tolerating absent metadata for optional in-flight records. */
		public void ifPresent(String hash, long size, String source) throws IOException {
			if (hash != null && !hash.isBlank()) add(hash, size, source);
		}

		private void add(String hash, long size, String source) throws IOException {
			String normalized;
			try {
				normalized = HashUtils.normalizeSha1(hash);
			} catch (RuntimeException e) {
				throw new IOException("Invalid object reference from " + source + ": " + hash, e);
			}
			if (!HashUtils.isSha1(normalized)) throw new IOException("Invalid object reference from " + source + ": " + hash);
			if (size < -1) throw new IOException("Invalid object size from " + source + ": " + size);
			Long previous = sizes.putIfAbsent(normalized, size);
			if (previous != null && size >= 0 && previous >= 0 && previous.longValue() != size) throw new IOException("Conflicting object sizes for " + normalized);
			if (previous != null && previous == -1 && size >= 0) sizes.put(normalized, size);
		}

		/** Records an additional expected size discovered while verifying, with the same conflict rules. */
		public void addExpectedSize(String hash, long expectedSize) throws IOException {
			add(hash, expectedSize, "verified reference");
		}

		public Set<String> hashes() {
			return Set.copyOf(sizes.keySet());
		}

		public Map<String, Long> sizes() {
			return Map.copyOf(sizes);
		}

		public Set<String> required() {
			return Set.copyOf(required);
		}
	}

	/** Overflow-checked addition used by every measurement receipt. */
	public static long addExact(long first, long second, String description) throws IOException {
		try {
			return Math.addExact(first, second);
		} catch (ArithmeticException e) {
			throw new IOException("Overflow while measuring " + description, e);
		}
	}

	public static FileTotals fileTotals(List<Path> paths) throws IOException {
		long bytes = 0;
		for (Path path : paths) bytes = addExact(bytes, Files.size(path), "object store bytes");
		return new FileTotals(paths.size(), bytes);
	}

	/** Lists every regular file beneath the sharded object store, rejecting symbolic links and unsupported entries. */
	public static List<Path> objectFiles(Path objectsDirectory) throws IOException {
		if (!Files.exists(objectsDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
		FileTrees.requireDirectory(objectsDirectory, "immutable objects");
		try (Stream<Path> shards = Files.list(objectsDirectory)) {
			List<Path> result = new ArrayList<>();
			for (Path shard : shards.sorted().toList()) {
				if (Files.isSymbolicLink(shard)) throw new IOException("Immutable object store contains a symbolic link: " + shard);
				if (Files.isRegularFile(shard, LinkOption.NOFOLLOW_LINKS)) continue;
				if (!Files.isDirectory(shard, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Immutable object store contains an unsupported entry: " + shard);
				try (Stream<Path> files = Files.list(shard)) {
					for (Path file : files.sorted().toList()) {
						if (Files.isSymbolicLink(file)) throw new IOException("Immutable object store contains a symbolic link: " + file);
						if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Immutable object store contains an unsupported entry: " + file);
						if (DataRootResolver.isObjectFile(objectsDirectory, file)) result.add(file);
					}
				}
			}
			return List.copyOf(result);
		}
	}

	/** Validates and normalizes a set of sha1 pins. */
	public static Set<String> canonicalPins(Set<String> pins, String description) throws IOException {
		TreeSet<String> result = new TreeSet<>();
		for (String pin : pins) {
			if (!HashUtils.isSha1(pin)) throw new IOException("Invalid " + description + " hash: " + pin);
			result.add(HashUtils.normalizeSha1(pin));
		}
		return Set.copyOf(result);
	}

	/**
	 * Deletes every canonical, full-read-verified object outside the reachable set. A concurrent
	 * collector removing an object between listing and stat is benign and skipped. The store
	 * directory is fsynced when anything was deleted.
	 */
	public static DeletionReceipt deleteUnreachable(Path objectsDirectory, Set<String> reachableHashes) throws IOException {
		List<Path> objects = objectFiles(objectsDirectory);
		long deletedCount = 0;
		long deletedBytes = 0;
		for (Path object : objects) {
			String hash = DataRootResolver.objectHash(objectsDirectory, object);
			if (hash == null || reachableHashes.contains(hash)) continue;
			if (!FileIntegrity.matchesCanonicalSha1(object, hash)) continue;
			long size;
			try {
				size = Files.size(object);
			} catch (NoSuchFileException e) {
				// Another collector removed the unreachable object between the listing and this stat.
				continue;
			}
			if (ImmutableFiles.deleteIfExists(object)) {
				deletedCount = addExact(deletedCount, 1, "deleted object count");
				deletedBytes = addExact(deletedBytes, size, "deleted object bytes");
			}
		}
		if (deletedCount > 0) FileTrees.forceDirectory(objectsDirectory);
		return new DeletionReceipt(deletedCount, deletedBytes);
	}
}
