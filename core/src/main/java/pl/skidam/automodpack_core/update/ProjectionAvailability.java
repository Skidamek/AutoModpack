package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.storage.StoragePaths.CLIENT_ACTIVE_DIR;
import static pl.skidam.automodpack_core.storage.StoragePaths.CLIENT_ACTIVE_STATE_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.CLIENT_SELECTED_FILE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;

/**
 * One process's answer about the active projection: whether a loader may take it now, whether a directory-scanning
 * loader already did, and whether this boot must relaunch because recovery changed what that loader took.
 *
 * <p>
 * A loader resolves the game directory before preload, so the scan decision is process state keyed by that directory.
 * Holding the decision, its failure and the persisted relaunch guard here gives every gate one question and leaves
 * {@link ClientStorage} with none of it.
 */
public final class ProjectionAvailability {
	private static final Logger LOGGER = LogManager.getLogger(ProjectionAvailability.class);

	private static final Map<Path, ProjectionAvailability> BY_GAME_DIRECTORY = new HashMap<>();
	private static final int SCAN_READ_ATTEMPTS = 5;
	private static final long SCAN_READ_RETRY_MILLIS = 100;
	static final long CONSECUTIVE_MILLIS = 5 * 60 * 1000L;
	private static final Gson GSON = ConfigTools.strictEnums(new GsonBuilder()).create();

	/**
	 * What the scan-time gate decided, from one read: the state it read (null when unreadable), whether that state
	 * refused the projection, whether {@code active/mods} went to the directory-scanning loaders, and the failure when
	 * the active state could not be read.
	 */
	public record Scan(ProjectionState state, boolean refusedByState, boolean exposed, IOException stateFailure) {
		public long revision() {
			return state == null ? -1 : state.revision();
		}
	}

	/** Why a load may not happen, with the failure behind it when there is one. */
	public record Refusal(String reason, IOException cause) {
		public boolean none() {
			return reason == null;
		}
	}

	static final class GuardFields {
		String content;
		long at;
	}

	private final Path gameDirectory;
	private volatile Scan scan;

	private ProjectionAvailability(Path gameDirectory) {
		this.gameDirectory = gameDirectory;
	}

	public static ProjectionAvailability of(Path gameDirectory) {
		Path key = gameDirectory.toAbsolutePath().normalize();
		synchronized (BY_GAME_DIRECTORY) {
			return BY_GAME_DIRECTORY.computeIfAbsent(key, ProjectionAvailability::new);
		}
	}

	/**
	 * The directory-scanning loader gate: {@code active/mods} when the loaders may take it, null when they may not. The
	 * directory loads when it exists, the projection state is settled (P3 of {@link ProjectionState}) and selected and
	 * active state do not disagree; an unreadable state file refuses. It runs before preload, so it reads files only, and
	 * it records the decision the relaunch guard compares against.
	 */
	public static Path loadableModsDirectory(Path gameDirectory) {
		return of(gameDirectory).expose();
	}

	/** What the gate decided this process, or null when no directory-scanning loader asked. */
	public static Scan scan(Path gameDirectory) {
		return of(gameDirectory).scan;
	}

	/** Whether a directory-scanning loader holds a decision this process's commits can invalidate. */
	public static boolean scanned(Path gameDirectory) {
		return of(gameDirectory).scan != null;
	}

	/** Test seam: one unit test stands in for several boots of the same game directory. */
	static void forget(Path gameDirectory) {
		Path key = gameDirectory.toAbsolutePath().normalize();
		synchronized (BY_GAME_DIRECTORY) {
			BY_GAME_DIRECTORY.remove(key);
		}
	}

	/** The projection state's own answer, for a load that has already checked the selection. */
	public static Refusal stateStanding(Path gameDirectory) {
		ProjectionState.Inspection inspection = ProjectionState.inspect(ProjectionState.file(gameDirectory));
		return new Refusal(inspection.refusal(), inspection.failure());
	}

	/** Why the authoritative load may not take {@code active/}: the state's answer plus selection agreement. */
	public static Refusal loadRefusal(ClientStorage storage) throws IOException {
		Refusal standing = stateStanding(storage.gameDirectory());
		if (!standing.none()) return standing;
		return new Refusal(ClientStorage.activeSelectionMismatch(storage.selectedModpackId(), storage.readActiveState()), null);
	}

	/** What the scan-time gate withheld from a load through the loader API, or none. */
	public static Refusal loaderApiWithholding(Path gameDirectory) {
		Scan current = of(gameDirectory).scan;
		if (current == null) return new Refusal(null, null);
		if (current.stateFailure() != null)
			return new Refusal("the scan-time projection gate could not read the client active state, so directory-scanning loaders did not receive the projection", current.stateFailure());
		if (current.refusedByState()) return new Refusal("directory-scanning loaders were refused the projection at scan time and this boot did not relaunch into it", null);
		return new Refusal(null, null);
	}

	/** Whether this boot must relaunch into the settled projection; the guard is recorded before the caller relaunches. */
	public static boolean mustRelaunch(ClientStorage storage) {
		return mustRelaunch(storage, of(storage.gameDirectory()).scan);
	}

	static boolean mustRelaunch(ClientStorage storage, Scan scan) {
		Path guard = storage.projectionRelaunchFile();
		try {
			if (scan == null) {
				Files.deleteIfExists(guard);
				return false;
			}
			ProjectionState.Inspection inspection = ProjectionState.inspect(storage);
			if (inspection.state() == null) {
				// Nothing loads this boot (every gate refuses), so a relaunch would load nothing more; the guard stays as it was.
				LOGGER.error("The projection state cannot be read after recovery; the projection does not load this boot", inspection.failure());
				return false;
			}
			ProjectionState now = inspection.state();
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
			ConfigTools.writeAtomicCompact(guard, fields);
		} catch (IOException e) {
			LOGGER.error("The projection relaunch guard cannot be updated; not relaunching, so no relaunch can repeat", e);
			return false;
		}
		LOGGER.info("Recovery changed the projection a directory-scanning loader decided on; restarting into it");
		return true;
	}

	private Path expose() {
		// One read decides the gate and is what the scan record keeps, so recovery compares against exactly what was decided.
		// A physical read failure this early is usually a startup scanner holding the file: the gate spends the longest
		// budget on it (5 x 100 ms) rather than refusing Kilt the projection and spending a relaunch on it.
		ProjectionState.Inspection inspection = ProjectionState.inspect(ProjectionState.file(gameDirectory), SCAN_READ_ATTEMPTS, SCAN_READ_RETRY_MILLIS);
		ProjectionState projection = inspection.state();
		String refusal = inspection.refusal();
		scan = new Scan(projection, false, false, null);
		Path activeDirectory = gameDirectory.resolve(CLIENT_ACTIVE_DIR).normalize();
		if (!Files.isDirectory(activeDirectory, LinkOption.NOFOLLOW_LINKS)) return null;
		Path activeModsDirectory = activeDirectory.resolve(ModpackPathPolicy.MODS_ROOT);
		if (!Files.isDirectory(activeModsDirectory, LinkOption.NOFOLLOW_LINKS)) return null;
		if (refusal != null) {
			LOGGER.warn("Not exposing the projection to directory-scanning loaders before recovery: {}", refusal);
			scan = new Scan(projection, true, false, null);
			return null;
		}

		ClientStorageJsons.ClientGenerationStateFields state;
		try {
			state = ConfigTools.readUnique(gameDirectory.resolve(CLIENT_ACTIVE_STATE_FILE).normalize(), ClientStorageJsons.ClientGenerationStateFields.class, "Client active state",
					ActivePackPointer::validated).orElse(null);
		} catch (IOException e) {
			// The state is durable, so this is a locked or unreadable file, not corrupt content. The gate cannot decide,
			// and returning null quietly would let preload load a projection the directory-scanning loaders never
			// received, so the failure is recorded and the authoritative load refuses to run on this boot.
			LOGGER.error("Failed to read the client active state; not exposing the projection to directory-scanning loaders", e);
			scan = new Scan(projection, false, false, e);
			return null;
		}
		ClientConfigJsons.SelectedModpackFields selected = ConfigTools.read(gameDirectory.resolve(CLIENT_SELECTED_FILE).normalize(), ClientConfigJsons.SelectedModpackFields.class).orElse(null);
		if (ClientStorage.activeSelectionMismatch(selected, state) != null) return null;
		scan = new Scan(projection, false, true, null);
		return activeModsDirectory;
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
