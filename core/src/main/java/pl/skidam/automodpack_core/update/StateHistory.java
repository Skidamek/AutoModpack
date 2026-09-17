package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.update.ClientStateJournal.Kind;
import pl.skidam.automodpack_core.update.ClientStateJournal.StateEntry;
import pl.skidam.automodpack_core.update.ClientStateJournal.TrackedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The user-facing view over the client state journal: the entry timeline, whether a past state can be restored whole
 * and through which reviewed flow, and per-file recovery out of any past state. Restores append their own checkpoints,
 * so the timeline stays a complete record of every file-state mutation.
 */
public final class StateHistory {
	private StateHistory() {}

	/** Whether one past state can be restored whole. The gate names itself so the UI can say why not. */
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

	/** One entry's restorability; the generation is the mirror entry the reviewed rollback restores, when there is one. */
	public record RestoreOption(Restorability restorability, JournalEntry generation) {
		public RestoreOption {
			Objects.requireNonNull(restorability, "restorability");
		}
	}

	public static List<StateEntry> entries(ClientStorage storage) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return ClientStateJournal.open(storage.stateHistoryJournalFile()).entries();
	}

	/** The one state entry of the journal, by sequence. */
	public static StateEntry entry(ClientStorage storage, long seq) throws IOException {
		return requireEntry(ClientStateJournal.open(storage.stateHistoryJournalFile()), seq);
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
	public static PreservationVault.OriginalRestore fileRestoreGate(ClientStorage storage, Root root, String path) throws IOException {
		Objects.requireNonNull(storage, "storage");
		return PreservationVault.LiveOwnership.read(storage).livePathRestore(root, path);
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
				PreservationVault.OriginalRestore gate = PreservationVault.LiveOwnership.read(storage).livePathRestore(root, path);
				switch (gate) {
					case AVAILABLE -> {
					}
					case NOT_GAME_DIR -> throw new IOException("Only game-directory files can be restored to their original path");
					default -> throw new IOException("The active modpack still owns " + path);
				}
				Path destination = storage.gamePath(path);
				PreservationVault.copyWithoutOverwrite(storage.gameDirectory(), storage.objectFile(file.sha1()), destination, file.size(), file.sha1(), cache);
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
				PreservationVault.copyWithoutOverwrite(storage.gameDirectory(), storage.objectFile(file.sha1()), destination, file.size(), file.sha1(), cache);
				return destination;
			}
		});
	}

	private static void appendFileRestore(ClientStateJournal journal, StateEntry source, TrackedFile file) throws IOException {
		StateEntry head = journal.head();
		List<TrackedFile> state = new ArrayList<>(head.state());
		state.removeIf(existing -> existing.root() == file.root() && existing.path().equals(file.path()));
		state.add(file);
		state.sort(StateEntry.STATE_ORDER);
		StateEntry checkpoint = new StateEntry(head.seq() + 1, "file-restore-" + UUID.randomUUID(), Kind.FILE_RESTORE, head.modpackId(), head.contentToken(), Instant.now(), source.seq(), state,
				List.of(ClientStateJournal.Change.install(file.root(), file.path(), null, file.sha1(), file.size())), List.of());
		journal.append(checkpoint);
	}

	private static StateEntry requireEntry(ClientStateJournal journal, long seq) throws IOException {
		return journal.entries().stream().filter(entry -> entry.seq() == seq).findFirst()
				.orElseThrow(() -> new IOException("No state history entry " + seq));
	}

	private static TrackedFile requireFile(StateEntry entry, Root root, String path) throws IOException {
		return entry.state().stream().filter(file -> file.root() == root && file.path().equals(path)).findFirst()
				.orElseThrow(() -> new IOException("State history entry " + entry.seq() + " does not track " + path));
	}
}
