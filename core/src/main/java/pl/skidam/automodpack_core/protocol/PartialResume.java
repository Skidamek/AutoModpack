package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The resume discipline both transports share: a stored partial is a resume prefix, and a partial past the expected end is
 * deleted with one loud line so the retry starts clean. The discipline also owns the wire side of resuming: a 206 may only
 * be appended behind the partial when the server actually resumed at the requested offset.
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

	/** The byte offset to resume from: the partial's size while it is a valid prefix of the expected object, zero for a fresh start. */
	public static long offset(Path partial, long expectedSize) {
		if (!Files.exists(partial)) return 0;
		long size;
		try {
			size = Files.size(partial);
		} catch (IOException e) {
			LOGGER.warn("Failed to inspect the partial {}; restarting from zero", partial.getFileName(), e);
			deleteQuietly(partial);
			return 0;
		}
		if (size > expectedSize) {
			LOGGER.warn("Stored partial for {} is past the served object's end; restarting from zero", partial.getFileName());
			deleteQuietly(partial);
			return 0;
		}
		return size;
	}

	static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}
}
