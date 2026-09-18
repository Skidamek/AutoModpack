package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.ClientStateJournal.Capture;
import pl.skidam.automodpack_core.update.ClientStateJournal.Change;
import pl.skidam.automodpack_core.update.ClientStateJournal.Kind;
import pl.skidam.automodpack_core.update.ClientStateJournal.StateEntry;
import pl.skidam.automodpack_core.update.ClientStateJournal.TrackedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The user-facing view over the client state journal: the entry timeline, whether a past state can be restored whole
 * and through which reviewed flow, per-file recovery out of any past state, and each pack's pre-install state.
 * Restores append their own checkpoints, so the timeline stays a complete record of every file-state mutation.
 */
public final class StateHistory {
	private StateHistory() {}

	/** What kind of committed mutation produced a state. Repairs and single-file restores append entries once their flows land on this journal. */
	public enum Restorability {
		/** This entry is where the active pack already is. */
		CURRENT,
		/** A clean generation state of the active pack whose bytes are still kept; restores through the reviewed rollback. */
		READY,
		/** A clean generation state whose bytes were compacted away. */
		NOT_KEPT,
		/** A clean generation state of another pack; activate it first. */
		INACTIVE_PACK,
		/** A state pieced together from single-file restores; restore files individually instead. */
		MIXED
	}

	/** The live gate for restoring one past file to its original path; Save copy stays available either way. */
	public enum FileGate {
		AVAILABLE, NOT_GAME_DIR, OWNED
	}

	/** One entry's restorability; the generation is the mirror entry the reviewed rollback restores, when there is one. */
	public record RestoreOption(Restorability restorability, JournalEntry generation) {
		public RestoreOption {
			Objects.requireNonNull(restorability, "restorability");
		}
	}

	/**
	 * One read of the live generation's ownership: the active state, its target, and every path that target owns.
	 * Building it costs a few durable parses; evaluating any number of files against it is pure, so batch callers
	 * resolve it once per operation instead of per file.
	 */
	private record LiveOwnership(SelectedModpackTarget activeTarget, FileCache cache, Set<String> generatedPaths, Set<String> flatTargetPaths) {
		static LiveOwnership read(ClientStorage storage, FileCache cache) throws IOException {
			ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
			SelectedModpackTarget activeTarget = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current()).orElse(null);
			String activePack = activeState == null ? null : activeState.modpackId;
			if (activeTarget == null || activePack == null || !activePack.equals(activeTarget.manifest().modpackId()))
				return new LiveOwnership(null, cache, Set.of(), Set.of());
			Set<String> generated = new HashSet<>();
			GeneratedCopyState.read(storage, activePack, activeTarget.packTarget().contentToken(), UpdateTransaction.digest(activeTarget.selection().intent())).entries()
					.forEach(entry -> generated.add(entry.logicalPath()));
			Set<String> flat = new HashSet<>();
			if (activeTarget.flatTarget().list != null) for (var item : activeTarget.flatTarget().list) flat.add(LogicalPath.normalize(item.file));
			return new LiveOwnership(activeTarget, cache, generated, flat);
		}

		FileGate fileGate(Root root, String logicalPath) {
			if (root != Root.GAME_DIR) return FileGate.NOT_GAME_DIR;
			String path = LogicalPath.normalize(logicalPath);
			if (activeTarget == null) return FileGate.AVAILABLE;
			if (generatedPaths.contains(path) || flatTargetPaths.contains(path)) return FileGate.OWNED;
			return FileGate.AVAILABLE;
		}
	}

	public static List<StateEntry> entries(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		// Locked, so a read that repairs a torn tail cannot race a concurrent append.
		return ClientStorageMutation.run(storage, () -> ClientStateJournal.open(storage.stateHistoryJournalFile()).entries());
	}

	/** The one state entry of the journal, by sequence. */
	public static StateEntry entry(ClientStorage storage, long seq) throws IOException {
		return ClientStorageMutation.run(storage, () -> requireEntry(ClientStateJournal.open(storage.stateHistoryJournalFile()), seq));
	}

	/**
	 * What restoring this entry whole would mean. Only clean generation states of the active pack qualify: their
	 * generation identity names a mirrored journal entry whose bytes the rollback machinery already knows how to serve.
	 */
	public static RestoreOption restorability(ClientStorage storage, StateEntry entry) throws IOException {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(entry, "entry");
		if (!isGenerationState(entry.kind())) return new RestoreOption(Restorability.MIXED, null);
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		if (active == null || !active.modpackId.equals(entry.modpackId())) return new RestoreOption(Restorability.INACTIVE_PACK, null);
		if (active.contentToken.equals(entry.contentToken())) return new RestoreOption(Restorability.CURRENT, null);
		for (JournalEntry candidate : new JournalMirror(storage).entries(entry.modpackId()))
			if (candidate.contentToken().equals(entry.contentToken()))
				return new RestoreOption(new ClientGenerationStore(storage).locallyRestorable(entry.modpackId(), candidate) ? Restorability.READY : Restorability.NOT_KEPT, candidate);
		return new RestoreOption(Restorability.NOT_KEPT, null);
	}

	private static boolean isGenerationState(Kind kind) {
		return kind == Kind.INSTALL || kind == Kind.UPDATE || kind == Kind.ROLLBACK;
	}

	/** The live gate for restoring one file to its original path, so the UI can disable Restore with the reason; Save copy stays available either way. */
	public static FileGate fileRestoreGate(ClientStorage storage, Root root, String path) throws IOException {
		Objects.requireNonNull(storage, "storage");
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			return LiveOwnership.read(storage, cache).fileGate(root, path);
		}
	}

	/**
	 * The pack's pre-install state, derived from its entries: the first capture per path wins, because a later capture
	 * names bytes a previous entry already tracks. Removal and cleanup restore from here.
	 */
	public static PreInstallState preInstallState(ClientStorage storage, String modpackId) throws IOException {
		ModpackId.requireValid(modpackId);
		Map<String, PreInstallState.Entry> entries = new TreeMap<>();
		for (StateEntry entry : entries(storage)) {
			if (!entry.modpackId().equals(modpackId)) continue;
			for (Capture capture : entry.captures())
				entries.putIfAbsent(capture.path(), new PreInstallState.Entry(capture.path(), capture.sha1(), capture.absent() ? 0 : capture.size(), capture.absent()));
		}
		return new PreInstallState(modpackId, entries);
	}

	/**
	 * Restores one tracked file of a past state to its original game path: unowned game-directory paths only, and
	 * never overwriting a different live file. Appends a {@code FILE_RESTORE} checkpoint whose lineage names the
	 * source entry, so the state after the restore is itself on the timeline.
	 */
	public static Path restoreFile(ClientStorage storage, long seq, Root root, String path) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return ClientStorageMutation.run(storage, () -> {
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				ClientStateJournal journal = ClientStateJournal.open(storage.stateHistoryJournalFile());
				StateEntry entry = requireEntry(journal, seq);
				TrackedFile file = requireFile(entry, root, path);
				FileGate gate = LiveOwnership.read(storage, cache).fileGate(root, path);
				if (gate == FileGate.NOT_GAME_DIR) throw new IOException("Only game-directory files can be restored to their original path");
				if (gate == FileGate.OWNED) throw new IOException("The active modpack still owns " + path);
				Path destination = storage.gamePath(path);
				// A destination that already holds exactly these bytes is a no-op restore; recording it would claim a
				// change that never happened.
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && FileIntegrity.matchesNamed(destination, file.size(), file.sha1(), cache)) return destination;
				copyWithoutOverwrite(storage.gameDirectory(), storage.objectFile(file.sha1()), destination, file.size(), file.sha1(), cache);
				appendFileRestore(journal, entry, file);
				return destination;
			}
		});
	}

	/** Saves {@code automodpack/recovered/{originalPath}} for one tracked file of a past state. No state changes, so no checkpoint. */
	public static Path saveFileCopy(ClientStorage storage, long seq, Root root, String path) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return ClientStorageMutation.run(storage, () -> {
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				StateEntry entry = requireEntry(ClientStateJournal.open(storage.stateHistoryJournalFile()), seq);
				TrackedFile file = requireFile(entry, root, path);
				Path destination = RecoveredFiles.destination(storage, path, file.sha1());
				copyWithoutOverwrite(storage.gameDirectory(), storage.objectFile(file.sha1()), destination, file.size(), file.sha1(), cache);
				return destination;
			}
		});
	}

	private static void appendFileRestore(ClientStateJournal journal, StateEntry source, TrackedFile file) throws IOException {
		StateEntry head = journal.head();
		TrackedFile previous = head.state().stream().filter(existing -> existing.root() == file.root() && existing.path().equals(file.path())).findFirst().orElse(null);
		List<TrackedFile> state = new ArrayList<>(head.state());
		state.removeIf(existing -> existing.root() == file.root() && existing.path().equals(file.path()));
		state.add(file);
		String fromHash = previous == null ? null : previous.sha1();
		long fromSize = previous == null ? 0 : previous.size();
		journal.appendCheckpoint("file-restore-" + UUID.randomUUID(), Kind.FILE_RESTORE, head.modpackId(), head.contentToken(), source.seq(), state,
				List.of(new Change(file.root(), file.path(), fromHash, fromSize, file.sha1(), file.size())), List.of());
	}

	private static StateEntry requireEntry(ClientStateJournal journal, long seq) throws IOException {
		return journal.entries().stream().filter(entry -> entry.seq() == seq).findFirst()
				.orElseThrow(() -> new IOException("No state history entry " + seq));
	}

	private static TrackedFile requireFile(StateEntry entry, Root root, String path) throws IOException {
		return entry.state().stream().filter(file -> file.root() == root && file.path().equals(path)).findFirst()
				.orElseThrow(() -> new IOException("State history entry " + entry.seq() + " does not track " + path));
	}

	/** Verifies the source object, never overwrites a different live file, and verifies the copy; shared by every journal-sourced restore. */
	static void copyWithoutOverwrite(Path constrainedRoot, Path source, Path destination, long size, String hash, FileCache cache) throws IOException {
		FileTrees.requireNoSymbolicLinkDescendants(constrainedRoot, destination, "restore destination");
		if (!FileIntegrity.matchesNamed(source, size, hash, cache)) throw new IOException("State history object is missing or corrupt: " + hash);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !FileIntegrity.matchesNamed(destination, size, hash, cache))
				throw new IOException("Refusing to overwrite a different file at " + destination);
			return;
		}
		VerifiedFileTransfer.copyCreateOnly(source, destination, size, hash, cache);
		FileTrees.requireNoSymbolicLinkDescendants(constrainedRoot, destination, "restore destination");
		if (!FileIntegrity.matchesNamed(destination, size, hash, cache)) throw new IOException("Restored file failed verification: " + destination);
	}
}
