package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The working state no journal owns any more, and the journal no apply can finish. Nothing here restores a generation
 * a journal could still roll forward: every apply rolls forward, so the only tree worth keeping is the one the game can
 * load right now.
 */
public final class PublicationSweep {
	private PublicationSweep() {}

	/**
	 * Drops what no journal will finish: the {@code incoming/} and {@code backup/} directories older versions published
	 * through, and a converging projection whose journal is gone, which goes back to its fallback (P4 of
	 * {@link ProjectionState}), so the game always has a complete projection.
	 */
	public static void sweepUnpinnedDirectories(ClientStorage storage) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			settleLegacyDirectories(storage);
			if (!Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS) && ProjectionState.owed(storage)) {
				LOGGER.warn("A projection convergence has no journal left to finish it; restoring the projection it started from");
				restoreFallback(storage);
			}
			return null;
		});
	}

	/**
	 * Settles the two directories an older version's swap left behind. Its swap moved {@code active/} to {@code backup/}
	 * and then {@code incoming/} to {@code active/}, so a crash between the moves left {@code backup/} as the only complete
	 * projection, which goes back to {@code active/}; otherwise {@code active/} is complete and both go. The caller of a
	 * journal that relied on them as its publication marker persists PROJECTED first.
	 */
	static void settleLegacyDirectories(ClientStorage storage) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			FileTrees.delete(storage.legacyIncomingDirectory());
			Path backup = storage.legacyBackupDirectory();
			if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
				if (Files.exists(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) FileTrees.delete(backup);
				else FileTrees.moveRecoverableDirectory(backup, storage.activeDirectory());
			}
			return null;
		});
	}

	/** P4: puts a converging projection's fallback back from CAS. Returns false while a held path keeps it converging. */
	public static boolean restoreFallback(ClientStorage storage) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
				return new ProjectionConvergence(storage, cache, ProjectionConvergence.Writes.NONE).restoreFallback();
			}
		});
	}

	/** Retires the pending journal to a quarantine file, the receipt of an update only a fresh review can re-approve; null when no journal is left. */
	public static Path retireJournal(ClientStorage storage) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			if (!Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) return null;
			Path stuckJournal = storage.clientDirectory().resolve("update-transaction.stuck-" + UUID.randomUUID() + ".json");
			Files.move(storage.transactionFile(), stuckJournal, StandardCopyOption.REPLACE_EXISTING);
			sweepUnpinnedDirectories(storage);
			return stuckJournal;
		});
	}
}
