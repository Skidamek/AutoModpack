package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import pl.skidam.automodpack_core.utils.FileTrees;

/**
 * The working directories no journal owns any more, and the journal no apply can finish. Nothing here restores a
 * generation: every apply rolls forward, so the only tree worth keeping is the one the game can load right now.
 */
public final class PublicationSweep {
	private PublicationSweep() {}

	/**
	 * Drops {@code incoming/} and {@code backup/} once no journal will publish them. A swap interrupted between its two
	 * moves left {@code backup/} as the only projection, which goes back to {@code active/}, so the game always has one.
	 * Left alone they read as a publication already started, which would make the next update skip its live operations.
	 */
	public static void sweepUnpinnedDirectories(ClientStorage storage) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			FileTrees.delete(storage.incomingDirectory());
			Path backup = storage.backupDirectory();
			if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
				if (Files.exists(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) FileTrees.delete(backup);
				else FileTrees.moveRecoverableDirectory(backup, storage.activeDirectory());
			}
			return null;
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
