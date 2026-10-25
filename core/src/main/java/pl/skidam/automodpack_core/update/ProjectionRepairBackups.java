package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.utils.FileTrees;

/**
 * V7 of {@code ProjectionConvergence}: the files a restore or a post-repair roll-forward removes or replaces that no
 * CAS object holds - a player's own file or edit - kept before they go.
 *
 * <p>
 * Content-addressed and bounded: each file lands at {@code projection-repair-backups/<sha1>/<path>}, so the same bytes
 * at the same path are kept once however many attempts meet them, and {@code INDEX.txt} lists every entry with its
 * time, hash, size and original path for the player. When the directory grows past {@link #LIMIT_BYTES} (1 GiB), the oldest
 * entries other than the ones just kept are pruned, and the log says which.
 */
public final class ProjectionRepairBackups {
	private ProjectionRepairBackups() {}

	public static final String DIRECTORY = "projection-repair-backups";
	public static final String INDEX = "INDEX.txt";
	static final long LIMIT_BYTES = 1L << 30;
	/** The bound in force; a test lowers it. */
	static volatile long limitBytes = LIMIT_BYTES;

	/** One kept file. */
	public record Entry(Instant at, String sha1, long size, String path, Path file) {}

	public static Path directory(ClientStorage storage) {
		return storage.clientDirectory().resolve(DIRECTORY);
	}

	/** Every kept file the index lists and that still exists, oldest first: what the installed-pack screens can show. */
	public static List<Entry> entries(ClientStorage storage) throws IOException {
		Path index = directory(storage).resolve(INDEX);
		List<Entry> entries = new ArrayList<>();
		if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) return entries;
		for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
			String[] fields = line.split("\t", 4);
			if (fields.length != 4) continue;
			try {
				Path file = directory(storage).resolve(fields[1]).resolve(fields[3]);
				if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) entries.add(new Entry(Instant.parse(fields[0]), fields[1], Long.parseLong(fields[2]), fields[3], file));
			} catch (RuntimeException malformed) {
				// A line a crash cut short names nothing.
			}
		}
		return entries;
	}

	/** Keeps {@code source} (bytes {@code sha1}, at projection path {@code path}) durably; returns the kept file, or null when an identical one already is. */
	static Path keep(ClientStorage storage, Path source, String path, String sha1, long size) throws IOException {
		Path root = directory(storage);
		Path kept = root.resolve(sha1).resolve(path).normalize();
		if (!kept.startsWith(root.resolve(sha1))) throw new IOException("Repair backup path escapes its directory: " + path);
		if (Files.isRegularFile(kept, LinkOption.NOFOLLOW_LINKS) && Files.size(kept) == size) return null;
		Files.createDirectories(kept.getParent());
		Path temporary = kept.resolveSibling("." + kept.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try {
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			FileTrees.forceFile(temporary);
			try {
				Files.move(temporary, kept, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, kept, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
		Files.writeString(root.resolve(INDEX), Instant.now() + "\t" + sha1 + "\t" + size + "\t" + path + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
				StandardOpenOption.SYNC);
		for (Path directory = kept.getParent(); directory != null && directory.startsWith(storage.clientDirectory()); directory = directory.getParent()) FileTrees.forceDirectory(directory);
		LOGGER.warn("Kept {} as {} before the projection replaced it: the store holds no copy of it. Every kept file is listed in {}", source, kept, root.resolve(INDEX));
		return kept;
	}

	/** Bounds the directory to the limit, pruning the oldest entries except {@code keep}. */
	static void bound(ClientStorage storage, Set<String> keep) throws IOException {
		Path root = directory(storage);
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
		record Bucket(Path directory, long bytes, FileTime modified) {}
		List<Bucket> buckets = new ArrayList<>();
		long total = 0;
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
				long bytes = size(child);
				total += bytes;
				buckets.add(new Bucket(child, bytes, Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS)));
			}
		}
		if (total <= limitBytes) return;
		buckets.sort(Comparator.comparing(Bucket::modified));
		for (Bucket bucket : buckets) {
			if (total <= limitBytes) break;
			if (keep.contains(bucket.directory().getFileName().toString())) continue;
			LOGGER.warn("Pruning the oldest projection repair backup {} ({} bytes): the backups exceed {} bytes", bucket.directory(), bucket.bytes(), limitBytes);
			FileTrees.delete(bucket.directory());
			total -= bucket.bytes();
		}
	}

	private static long size(Path directory) throws IOException {
		long[] total = {0};
		Files.walkFileTree(directory, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				if (attributes.isRegularFile()) total[0] += attributes.size();
				return FileVisitResult.CONTINUE;
			}
		});
		return total[0];
	}
}
