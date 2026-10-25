package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance.DeletionReceipt;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Incomplete objects live as a directory of slices: {@code staging/<sha1>/<offset>} is one whole 4 MiB tile.
 *
 * <p>
 * A tile being written is at {@code <offset>.tmp} and only takes its final name once every one of its bytes is on disk,
 * so a slice at its final name is whole and nothing else. Every reader below follows from that one fact: coverage is the
 * directory listing of final names, promotion concatenates them in offset order, and two writers on one object never
 * have to agree about how far along the other one is. Contention on a tile is settled by the rename, since the first
 * writer to finish moves its temp onto the name and the second finds the name taken.
 */
public final class PartialResume {

	private static final Logger LOGGER = LogManager.getLogger();

	private static final int ASSEMBLY_BUFFER = 256 * 1024;

	private static final String HELD_PREFIX = ".held.";
	private static final String HELD_SUFFIX = ".obj";

	private static final String HOST_SUFFIX = ".host" + DurableFiles.TEMPORARY_SUFFIX;

	private PartialResume() {}

	/**
	 * The Content-Range start of a 206 answer: the offset the server actually resumed at. A missing header or a start
	 * past the requested offset means the stored partial is stale and the retry must start from zero; an unparseable
	 * header is a broken or hostile peer.
	 */
	public static long requireResumeStart(String contentRange, long offset) throws IOException {
		if (contentRange == null) throw new StaleRangeException();
		String spec = contentRange.trim();
		if (!spec.startsWith("bytes ")) throw new IOException("Unparseable Content-Range: " + contentRange);
		int dash = spec.indexOf('-');
		if (dash < 0) throw new IOException("Unparseable Content-Range: " + contentRange);
		long start;
		try {
			start = Long.parseLong(spec.substring("bytes ".length(), dash).trim());
		} catch (NumberFormatException e) {
			throw new IOException("Unparseable Content-Range: " + contentRange);
		}
		if (start != offset) throw new StaleRangeException();
		return start;
	}

	public static Path directory(Path stagingRoot, String sha1) {
		return stagingRoot.resolve(HashUtils.normalizeSha1(sha1));
	}

	public static Path sliceFile(Path directory, long sliceStart) {
		return directory.resolve(Long.toString(sliceStart));
	}

	/** Where a tile is written while it is still filling. Readers ignore these; only a writer adopts one. */
	public static Path sliceTemp(Path directory, long sliceStart) {
		return directory.resolve(Long.toString(sliceStart) + DurableFiles.TEMPORARY_SUFFIX);
	}

	/**
	 * A host take's own temp for the same tile. It is separate from the platform's so the two never hold two writers on one
	 * file: both write the same bytes, and the rename decides which copy is kept, so they cannot disagree about content.
	 */
	public static Path sliceHostTemp(Path directory, long sliceStart) {
		return directory.resolve(Long.toString(sliceStart) + HOST_SUFFIX);
	}

	public static long sliceStart(long offset) {
		return offset / NetUtils.WIRE_CHUNK_BYTES * (long) NetUtils.WIRE_CHUNK_BYTES;
	}

	public static long sliceLength(long sliceStart, long fileSize) {
		return Math.min(NetUtils.WIRE_CHUNK_BYTES, fileSize - sliceStart);
	}

	/**
	 * Whether this tile is whole, as its whole length or as nothing. A slice at its final name is only ever named by a
	 * completed write, so a size that does not match exactly is something else and is discarded rather than read as progress.
	 */
	public static long presentLength(Path slice, long expected) throws IOException {
		if (!Files.isRegularFile(slice)) return 0;
		if (Files.size(slice) == expected) return expected;
		LOGGER.warn("Stored slice {} is not a whole {} byte tile; discarding it", slice.getFileName(), expected);
		Files.deleteIfExists(slice);
		return 0;
	}

	/**
	 * Moves a finished temp onto its tile name and reports whether it won. Losing means another writer got there first, and
	 * the loser's bytes are the same bytes, so the tile is already correct and the copy is dropped.
	 */
	public static boolean promoteSlice(Path directory, long sliceStart, long fileSize) throws IOException {
		Path tile = sliceFile(directory, sliceStart);
		if (Files.isRegularFile(tile)) return false;
		// Only a full tile is ever named, so a cut-short take stays a temp and no reader mistakes it for progress.
		Path temp = null;
		for (Path candidate : List.of(sliceTemp(directory, sliceStart), sliceHostTemp(directory, sliceStart))) {
			if (Files.isRegularFile(candidate) && Files.size(candidate) == sliceLength(sliceStart, fileSize)) {
				temp = candidate;
				break;
			}
		}
		if (temp == null) return false;
		try {
			Files.move(temp, tile);
			return true;
		} catch (FileAlreadyExistsException lost) {
			discard(temp);
			return false;
		}
	}

	private static void discard(Path temp) {
		try {
			Files.deleteIfExists(temp);
		} catch (IOException ignored) {
		}
	}

	/**
	 * Bytes already in a tile that is still filling, which is a resume point but not progress: it is counted here and never
	 * by {@link #presentBytes}, so an interrupted transfer continues where it stopped without the total ever claiming twice.
	 */
	public static long inProgressLength(Path temp, long expected) throws IOException {
		if (!Files.isRegularFile(temp)) return 0;
		long size = Files.size(temp);
		return size < expected ? size : expected;
	}

	/** How far into its tile a writer starts: behind a whole tile nothing, behind a filling one its bytes. */
	public static long resumeOffset(Path directory, long sliceStart, long fileSize) throws IOException {
		long expected = sliceLength(sliceStart, fileSize);
		return Math.max(presentLength(sliceFile(directory, sliceStart), expected), inProgressLength(sliceTemp(directory, sliceStart), expected));
	}

	/**
	 * Remaining wire ranges as {@code [absStart, absEnd]} inclusive, lowest tile first. A tile somebody else filled whole
	 * is skipped, and one still filling contributes a range that starts behind the bytes already on disk.
	 */
	public static List<long[]> remaining(Path directory, long fileSize) throws IOException {
		List<long[]> work = new ArrayList<>();
		if (fileSize <= 0) return work;
		Files.createDirectories(directory);
		for (long start = 0; start < fileSize; start += NetUtils.WIRE_CHUNK_BYTES) {
			long expected = sliceLength(start, fileSize);
			if (presentLength(sliceFile(directory, start), expected) >= expected) continue;
			long from = resumeOffset(directory, start, fileSize);
			if (from < expected) work.add(new long[]{start + from, start + expected - 1});
		}
		return work;
	}

	public static long presentBytes(Path directory, long fileSize) throws IOException {
		if (fileSize <= 0 || !Files.isDirectory(directory)) return 0;
		long bytes = 0;
		for (long start = 0; start < fileSize; start += NetUtils.WIRE_CHUNK_BYTES)
			bytes += presentLength(sliceFile(directory, start), sliceLength(start, fileSize));
		return bytes;
	}

	/** Bytes still to fetch for this object: the advertised size minus finished staging slices. */
	public static long remainingBytes(Path stagingRoot, String sha1, long fileSize) throws IOException {
		if (fileSize <= 0) return 0;
		return Math.max(0, fileSize - presentBytes(directory(stagingRoot, sha1), fileSize));
	}

	public static boolean complete(Path directory, long fileSize) throws IOException {
		return remaining(directory, fileSize).isEmpty();
	}

	/**
	 * Where a downloader writing from {@code offset} should stop: the start of the first whole tile ahead of it, or the
	 * end of the object when there is none. Stopping there is what keeps one side from re-fetching tiles the other filled,
	 * and it only costs a request when there is actually a whole tile to step over.
	 */
	public static long stopBefore(Path directory, long offset, long fileSize) throws IOException {
		for (long start = sliceStart(offset) + NetUtils.WIRE_CHUNK_BYTES; start < fileSize; start += NetUtils.WIRE_CHUNK_BYTES)
			if (presentLength(sliceFile(directory, start), sliceLength(start, fileSize)) >= sliceLength(start, fileSize)) return start;
		return fileSize;
	}

	/** The first missing object byte, or {@code fileSize} when every tile is present. */
	public static long nextByte(Path directory, long fileSize) throws IOException {
		List<long[]> work = remaining(directory, fileSize);
		return work.isEmpty() ? fileSize : work.get(0)[0];
	}

	public static void assemble(Path directory, Path destination, long fileSize) throws IOException {
		assembleHashing(directory, destination, fileSize);
	}

	/**
	 * Stitches the tiles into {@code destination} like {@link #assemble} and returns the SHA-1 of exactly the bytes written,
	 * so promotion never has to read the stitched copy back to verify it.
	 */
	public static String assembleHashing(Path directory, Path destination, long fileSize) throws IOException {
		if (fileSize < 0) throw new IOException("Negative object size");
		if (!complete(directory, fileSize)) throw new IOException("Incomplete slices for " + directory.getFileName());
		Path parent = destination.getParent();
		if (parent != null) Files.createDirectories(parent);
		MessageDigest digest = HashUtils.newSha1Digest();
		byte[] buffer = new byte[ASSEMBLY_BUFFER];
		long written = 0;
		try (OutputStream out = Files.newOutputStream(destination)) {
			for (long start = 0; start < fileSize; start += NetUtils.WIRE_CHUNK_BYTES) {
				try (InputStream in = Files.newInputStream(sliceFile(directory, start))) {
					int read;
					while ((read = in.read(buffer)) != -1) {
						digest.update(buffer, 0, read);
						out.write(buffer, 0, read);
						written += read;
					}
				}
			}
		}
		if (written != fileSize || Files.size(destination) != fileSize) throw new IOException("Assembled object size does not match " + fileSize);
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Moves the only tile of a one-tile object to {@code destination}, a private name on the same filesystem, so the
	 * caller can verify and publish it without a copy. False leaves the tile where it was, and the caller stitches instead.
	 */
	public static boolean adoptSoleTile(Path directory, Path destination, long fileSize) throws IOException {
		if (fileSize <= 0 || fileSize > NetUtils.WIRE_CHUNK_BYTES) return false;
		Path tile = sliceFile(directory, 0);
		if (presentLength(tile, fileSize) != fileSize) return false;
		try {
			Files.move(tile, destination);
		} catch (IOException couldNotMove) {
			// A scanner holding the tile open on Windows, or a filesystem without plain renames: stitching still works.
			LOGGER.debug("Could not move sole tile {} into place, stitching it instead", tile, couldNotMove);
			return false;
		}
		return true;
	}

	/**
	 * A fresh name inside the slice directory for a verified object waiting for its batched sync. Held objects are never
	 * tiles, so no reader counts them as progress, and the sweepers that drop slice directories drop them too.
	 */
	public static Path newHeldFile(Path directory) {
		return directory.resolve(HELD_PREFIX + UUID.randomUUID() + HELD_SUFFIX);
	}

	/** Objects an earlier run verified but may not have synced: untrusted bytes, to be hashed again before any use. */
	public static List<Path> heldFiles(Path directory) throws IOException {
		List<Path> held = new ArrayList<>();
		if (!Files.isDirectory(directory)) return held;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, HELD_PREFIX + "*" + HELD_SUFFIX)) {
			for (Path file : files) if (Files.isRegularFile(file)) held.add(file);
		}
		return held;
	}

	/** Drops the named tiles once their bytes live in a stitched copy, so a held object does not keep its size twice. */
	public static void deleteTiles(Path directory, long fileSize) throws IOException {
		for (long start = 0; start < fileSize; start += NetUtils.WIRE_CHUNK_BYTES) Files.deleteIfExists(sliceFile(directory, start));
	}

	/** Sequential writer that fills tiles from {@code absStart} onward; each completed tile is just that file. */
	public static OutputStream writer(Path directory, long fileSize, long absStart) throws IOException {
		Files.createDirectories(directory);
		return new SliceWriter(directory, fileSize, absStart);
	}

	public static void delete(Path directory) {
		try {
			if (directory != null) FileTrees.delete(directory);
		} catch (IOException ignored) {
		}
	}

	/** Drops every {@code staging/<sha1>/} tree. Other staging files (publication temps) stay. */
	public static DeletionReceipt wipeSliceDirectories(Path stagingRoot) throws IOException {
		long count = 0;
		long bytes = 0;
		if (!Files.isDirectory(stagingRoot)) return new DeletionReceipt(0, 0);
		try (DirectoryStream<Path> children = Files.newDirectoryStream(stagingRoot)) {
			for (Path child : children) {
				if (!isSliceDirectory(child)) continue;
				long size = directoryBytes(child);
				FileTrees.delete(child);
				count++;
				bytes += size;
			}
		}
		return new DeletionReceipt(count, bytes);
	}

	/** Drops slice directories whose sha1 is not in {@code needed}. */
	public static void keepOnly(Path stagingRoot, Set<String> needed) throws IOException {
		if (!Files.isDirectory(stagingRoot)) return;
		try (DirectoryStream<Path> children = Files.newDirectoryStream(stagingRoot)) {
			for (Path child : children) {
				if (!isSliceDirectory(child)) continue;
				if (needed.contains(child.getFileName().toString())) continue;
				FileTrees.delete(child);
			}
		}
	}

	private static boolean isSliceDirectory(Path path) {
		return Files.isDirectory(path) && HashUtils.isCanonicalSha1(path.getFileName().toString());
	}

	private static long directoryBytes(Path directory) throws IOException {
		long bytes = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
			for (Path file : files) {
				if (Files.isRegularFile(file)) bytes += Files.size(file);
			}
		}
		return bytes;
	}

	private static final class SliceWriter extends OutputStream {
		private final Path directory;
		private final long fileSize;
		private long pos;
		private OutputStream current;
		private long currentStart;
		private long currentEnd;

		SliceWriter(Path directory, long fileSize, long absStart) {
			this.directory = directory;
			this.fileSize = fileSize;
			this.pos = absStart;
		}

		@Override
		public void write(int value) throws IOException {
			write(new byte[]{(byte) value}, 0, 1);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			while (length > 0) {
				if (pos >= fileSize) return;
				if (current == null) open();
				if (current == null) return;
				int n = (int) Math.min(length, currentEnd - pos);
				current.write(bytes, offset, n);
				pos += n;
				offset += n;
				length -= n;
				if (pos == currentEnd) closeCurrent();
			}
		}

		/**
		 * Opens the tile this position falls in. A tile somebody else already finished whole is discarded from the
		 * stream rather than rewritten, so the next incoming byte still lands at the matching file offset.
		 */
		private void open() throws IOException {
			if (pos >= fileSize) return;
			long start = sliceStart(pos);
			long expected = sliceLength(start, fileSize);
			currentStart = start;
			currentEnd = start + expected;
			if (presentLength(sliceFile(directory, start), expected) >= expected) {
				current = OutputStream.nullOutputStream();
				return;
			}
			Path temp = sliceTemp(directory, start);
			long offsetInTile = pos - start;
			current = offsetInTile == 0 ? LocalFileWriter.open(temp) : LocalFileWriter.openAt(temp, offsetInTile);
		}

		/** A tile that filled completely is named; one cut short stays a temp and is picked up or dropped on the next open. */
		private void closeCurrent() throws IOException {
			current.close();
			current = null;
			promoteSlice(directory, currentStart, fileSize);
		}

		@Override
		public void close() throws IOException {
			if (current != null) current.close();
		}
	}
}
