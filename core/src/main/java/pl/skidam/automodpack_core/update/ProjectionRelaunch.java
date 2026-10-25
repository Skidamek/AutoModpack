package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import pl.skidam.automodpack_core.utils.DurableFiles;

/**
 * P5 of {@link ProjectionState} for directory-scanning loaders (Kilt): they take {@code active/mods} before preload,
 * from what the scan-time gate decided on its one read ({@link ClientStorage.DirectoryScan}). When recovery leaves a
 * settled projection whose content is not what the gate handed out - it moved, or the gate refused a projection that
 * is loadable now - the boot relaunches into it. A persisted one-shot guard bounds that to one relaunch whatever
 * recovery keeps doing; a boot that needs none clears it.
 */
public final class ProjectionRelaunch {
	private ProjectionRelaunch() {}

	/** Whether this boot must relaunch now; true only once in a row, recorded durably before the caller relaunches. */
	public static boolean required(ClientStorage storage, ClientStorage.DirectoryScan scan) {
		if (scan == null) return false;
		ProjectionState now;
		try {
			now = ProjectionState.read(storage);
		} catch (IOException e) {
			LOGGER.error("The projection state cannot be read after recovery; the projection does not load this boot", e);
			return false;
		}
		// A projection left converging loads nowhere this boot, so no relaunch would load more.
		if (now.converging()) return false;
		boolean refusedNowLoadable = scan.refusedByState() && Files.isDirectory(storage.activePath("mods"), LinkOption.NOFOLLOW_LINKS);
		boolean moved = scan.exposed() && (scan.state() == null || !now.sameContentAs(scan.state()));
		Path guard = storage.projectionRelaunchFile();
		try {
			if (!refusedNowLoadable && !moved) {
				Files.deleteIfExists(guard);
				return false;
			}
			if (Files.exists(guard, LinkOption.NOFOLLOW_LINKS)) {
				LOGGER.warn("Recovery changed the projection a directory-scanning loader decided on (revision {} -> {}) again right after a relaunch for it; this session runs without what it missed",
						scan.revision(), now.revision());
				return false;
			}
			DurableFiles.writeAtomic(guard, ("{\"revision\":" + now.revision() + "}").getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOGGER.error("The projection relaunch guard cannot be updated; not relaunching, so no relaunch can repeat", e);
			return false;
		}
		LOGGER.info("Recovery changed the projection a directory-scanning loader decided on (revision {} -> {}); restarting", scan.revision(), now.revision());
		return true;
	}
}
