package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import com.google.gson.Gson;

import pl.skidam.automodpack_core.utils.DurableFiles;

/**
 * P5 of {@link ProjectionState} for directory-scanning loaders (Kilt): they take {@code active/mods} before preload,
 * from what the scan-time gate decided on its one read ({@link ClientStorage.DirectoryScan}). When recovery leaves a
 * settled projection whose content is not what the gate handed out - it moved, or the gate refused a projection that
 * is loadable now - the boot relaunches into it.
 *
 * <p>
 * A persisted guard bounds that, keyed to what it relaunched for (the content the relaunch was to load: its digest,
 * else its revision) and when: a need for the same content again, or any need within {@link #CONSECUTIVE_MILLIS} of
 * the last relaunch, does not relaunch, so a relaunch never repeats into the same state and never loops. Every boot
 * that needs no relaunch - a directory scan that matched, no directory scan at all, a projection left converging -
 * clears it, so a guard never outlives the relaunch it bounded and never suppresses a later, different one.
 */
public final class ProjectionRelaunch {
	private ProjectionRelaunch() {}

	static final long CONSECUTIVE_MILLIS = 5 * 60 * 1000L;

	static final class GuardFields {
		String content;
		long at;
	}

	private static final Gson GSON = new Gson();

	/** Whether this boot must relaunch now; recorded durably before the caller relaunches. */
	public static boolean required(ClientStorage storage, ClientStorage.DirectoryScan scan) {
		Path guard = storage.projectionRelaunchFile();
		try {
			if (scan == null) {
				Files.deleteIfExists(guard);
				return false;
			}
			ProjectionState now;
			try {
				now = ProjectionState.read(storage);
			} catch (IOException e) {
				// Nothing loads this boot (every gate refuses), so a relaunch would load nothing more; the guard stays as it was.
				LOGGER.error("The projection state cannot be read after recovery; the projection does not load this boot", e);
				return false;
			}
			boolean refusedNowLoadable = !now.converging() && scan.refusedByState() && Files.isDirectory(storage.activePath("mods"), LinkOption.NOFOLLOW_LINKS);
			boolean moved = !now.converging() && scan.exposed() && (scan.state() == null || !now.sameContentAs(scan.state()));
			if (!refusedNowLoadable && !moved) {
				Files.deleteIfExists(guard);
				return false;
			}
			String content = now.contentDigest() != null ? "digest:" + now.contentDigest() : "revision:" + now.revision();
			long time = System.currentTimeMillis();
			GuardFields last = readGuard(guard);
			if (last != null && (content.equals(last.content) || last.at <= time && time - last.at < CONSECUTIVE_MILLIS)) {
				LOGGER.warn("Recovery changed the projection a directory-scanning loader decided on (revision {} -> {}) right after a relaunch for it; this session runs without what it missed",
						scan.revision(), now.revision());
				return false;
			}
			GuardFields fields = new GuardFields();
			fields.content = content;
			fields.at = time;
			DurableFiles.writeAtomic(guard, GSON.toJson(fields).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOGGER.error("The projection relaunch guard cannot be updated; not relaunching, so no relaunch can repeat", e);
			return false;
		}
		LOGGER.info("Recovery changed the projection a directory-scanning loader decided on; restarting into it");
		return true;
	}

	private static GuardFields readGuard(Path guard) {
		try {
			GuardFields fields = GSON.fromJson(Files.readString(guard, StandardCharsets.UTF_8), GuardFields.class);
			if (fields != null && fields.content != null) return fields;
		} catch (NoSuchFileException missing) {
			return null;
		} catch (IOException | RuntimeException unusable) {
			// An unreadable guard still marks a relaunch that happened; treat it as a recent one.
		}
		GuardFields recent = new GuardFields();
		recent.content = "";
		recent.at = System.currentTimeMillis();
		return recent;
	}
}
