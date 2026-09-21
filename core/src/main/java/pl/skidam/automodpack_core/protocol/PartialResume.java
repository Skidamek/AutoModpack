package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The resume discipline both transports share: a stored partial is a resume prefix, and a partial past the expected end is
 * deleted with one loud line so the retry starts clean. The host wire's DownloadClient still carries its own copy of this
 * logic; both forms must stay in lockstep until it adopts this one.
 */
public final class PartialResume {

	private static final Logger LOGGER = LogManager.getLogger();

	private PartialResume() {}

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

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}
}
