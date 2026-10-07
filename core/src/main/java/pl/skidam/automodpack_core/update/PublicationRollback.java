package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.utils.FileTrees;

/**
 * The rollback half of an update publication: drop an in-flight publication and restore the last finalized generation.
 * It applies no plan, so it answers to a different invariant than {@link UpdateCommit} - {@code backup/} is the previous
 * committed tree only until finalize wrote the active state, and a rollback that cannot tell the two apart either
 * restores a tree the player already moved past or deletes the only full copy of the good one.
 */
public final class PublicationRollback {
	private final ClientStorage storage;

	public PublicationRollback(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage, "storage");
	}

	/**
	 * The stuck-update escape: drop in-flight publication, keep the last finalized generation, and retire the journal to
	 * a quarantine file so the receipt survives. {@code backup/} is the previous committed tree only while finalize has
	 * not yet written the active state.
	 */
	public Path abandonStuckPublication(UpdateTransaction transaction) throws IOException {
		Objects.requireNonNull(transaction, "transaction");
		return ClientStorageMutation.run(storage, () -> {
			revertUnfinalizedPublicationPersisted(transaction);
			// The journal may already be retired: a replayed predecessor's tail deletes it before this transaction's own
			// failure path reaches here, and a journal that is already gone has nothing left to quarantine.
			if (!Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) return null;
			Path stuckJournal = storage.clientDirectory().resolve("update-transaction.stuck-" + UUID.randomUUID() + ".json");
			Files.move(storage.transactionFile(), stuckJournal, StandardCopyOption.REPLACE_EXISTING);
			return stuckJournal;
		});
	}

	/** Drops in-flight publication and restores the last finalized generation, keeping {@code backup/} only while finalize has not yet written the active state. */
	public void revertUnfinalizedPublication(UpdateTransaction transaction) throws IOException {
		Objects.requireNonNull(transaction, "transaction");
		ClientStorageMutation.run(storage, () -> {
			revertUnfinalizedPublicationPersisted(transaction);
			return null;
		});
	}

	/**
	 * With no usable pending transaction the publication directories are provably unpinned - no journal owns their
	 * bytes - so leftovers of a crash whose journal was lost or set aside are swept here. Left alone they read as a
	 * publication already started, which would make the next update silently skip every live operation and its
	 * final-state verification while its commit reports success.
	 */
	public static void sweepUnpinnedDirectories(ClientStorage storage) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			FileTrees.delete(storage.incomingDirectory());
			FileTrees.delete(storage.backupDirectory());
			return null;
		});
	}

	private void revertUnfinalizedPublicationPersisted(UpdateTransaction transaction) throws IOException {
		Path active = storage.activeDirectory();
		Path backup = storage.backupDirectory();
		FileTrees.delete(storage.incomingDirectory());
		if (!generationAlreadyFinalized(transaction) && Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) {
			FileTrees.delete(active);
			FileTrees.moveRecoverableDirectory(backup, active);
		} else {
			FileTrees.delete(backup);
		}
	}

	private boolean generationAlreadyFinalized(UpdateTransaction transaction) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL || transaction.purpose == UpdateTransaction.Purpose.MODPACK_DEACTIVATION) return state == null;
		return state != null && transaction.plan().packTarget().contentToken() != null && transaction.plan().packTarget().contentToken().equals(state.contentToken);
	}
}
