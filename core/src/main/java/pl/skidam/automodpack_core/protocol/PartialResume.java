package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.storage.ObjectStoreMaintenance.DeletionReceipt;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * Incomplete objects live as a directory of finished slices: {@code staging/<sha1>/<offset>} is the bytes of that
 * 4 MiB tile. A short file is a prefix of its tile and the next take resumes behind it. Coverage is the directory
 * listing; promotion concatenates in offset order and hashes the whole.
 */
public final class PartialResume {

	private static final Logger LOGGER = LogManager.getLogger();

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

	public static long sliceStart(long offset) {
		return offset / NetUtils.WIRE_CHUNK_BYTES * (long) NetUtils.WIRE_CHUNK_BYTES;
	}

	public static long sliceLength(long sliceStart, long fileSize) {
		return Math.min(NetUtils.WIRE_CHUNK_BYTES, fileSize - sliceStart);
	}

	/** Bytes already stored for this tile: 0 if missing or past the expected end (the junk file is deleted). */
	public static long presentLength(Path slice, long expected) throws IOException {
		if (!Files.isRegularFile(slice)) return 0;
		long size = Files.size(slice);
		if (size <= expected) return size;
		LOGGER.warn("Stored slice {} is past its {} byte tile; discarding it", slice.getFileName(), expected);
		Files.deleteIfExists(slice);
		return 0;
	}

	/**
	 * Remaining wire ranges as {@code [absStart, absEnd]} inclusive, lowest tile first. A short tile contributes a
	 * range that starts behind its prefix.
	 */
	public static List<long[]> remaining(Path directory, long fileSize) throws IOException {
		List<long[]> work = new ArrayList<>();
		if (fileSize <= 0) return work;
		Files.createDirectories(directory);
		for (long start = 0; start < fileSize; start += NetUtils.WIRE_CHUNK_BYTES) {
			long expected = sliceLength(start, fileSize);
			long present = presentLength(sliceFile(directory, start), expected);
			if (present < expected) work.add(new long[]{start + present, start + expected - 1});
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

	public static boolean complete(Path directory, long fileSize) throws IOException {
		return remaining(directory, fileSize).isEmpty();
	}

	/** The first missing object byte, or {@code fileSize} when every tile is present. */
	public static long nextByte(Path directory, long fileSize) throws IOException {
		List<long[]> work = remaining(directory, fileSize);
		return work.isEmpty() ? fileSize : work.get(0)[0];
	}

	public static void assemble(Path directory, Path destination, long fileSize) throws IOException {
		if (fileSize < 0) throw new IOException("Negative object size");
		if (!complete(directory, fileSize)) throw new IOException("Incomplete slices for " + directory.getFileName());
		Path parent = destination.getParent();
		if (parent != null) Files.createDirectories(parent);
		if (fileSize == 0) {
			if (!Files.exists(destination)) Files.createFile(destination);
			return;
		}
		try (OutputStream out = Files.newOutputStream(destination)) {
			for (long start = 0; start < fileSize; start += NetUtils.WIRE_CHUNK_BYTES)
				Files.copy(sliceFile(directory, start), out);
		}
		if (Files.size(destination) != fileSize) throw new IOException("Assembled object size does not match " + fileSize);
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
				if (pos >= fileSize) throw new IOException("Slice writer passed the object end");
				if (current == null) open();
				int n = (int) Math.min(length, currentEnd - pos);
				current.write(bytes, offset, n);
				pos += n;
				offset += n;
				length -= n;
				if (pos == currentEnd) closeCurrent();
			}
		}

		private void open() throws IOException {
			long start = sliceStart(pos);
			Path slice = sliceFile(directory, start);
			long expected = sliceLength(start, fileSize);
			long present = presentLength(slice, expected);
			if (pos != start + present) throw new IOException("Slice writer is not aligned with stored prefix at " + pos);
			current = present > 0 ? LocalFileWriter.openAt(slice, present) : LocalFileWriter.open(slice);
			currentEnd = start + expected;
		}

		private void closeCurrent() throws IOException {
			current.close();
			current = null;
		}

		@Override
		public void close() throws IOException {
			if (current != null) closeCurrent();
		}
	}
}
