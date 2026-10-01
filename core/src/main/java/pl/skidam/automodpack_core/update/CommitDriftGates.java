package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.update.UpdateTransactionValidator.MutableInputDrift;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Every mutable-input drift gate of one commit, in the order the apply walks past them: the configuration and
 * selection facts a plan assumed and that a concurrent change invalidates. The recovery triggers ask the same
 * question through {@link #replanRequired}, qualified by how far the publication already went.
 */
final class CommitDriftGates {
	private final ClientStorage storage;
	private final UpdateTransactionValidator validator;
	private final FileCache fileCache;

	CommitDriftGates(ClientStorage storage, UpdateTransactionValidator validator, FileCache fileCache) {
		this.storage = storage;
		this.validator = validator;
		this.fileCache = fileCache;
	}

	/** The recovery triggers' question: has any mutable input moved such that the plan must be built again before mutation continues. */
	boolean replanRequired(UpdateTransaction transaction) throws IOException {
		if (transaction == null) return false;
		MutableInputDrift drift = validator.mutableInputDrift(transaction, fileCache);
		if (ClientProjectionView.publicationStarted(storage, transaction))
			return drift.configuration() || transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE && (!overlayStateMatches(transaction) || drift.selection());
		return drift.any();
	}

	/** The pre-mutation configuration gate; a publication that already started cannot undo its config side effects, so it is exempt. */
	void beforeFirstMutation(UpdateTransaction transaction, boolean publicationStarted) throws IOException {
		if (!publicationStarted && validator.mutableInputDrift(transaction, fileCache).configuration())
			throw new UpdateReplanRequiredException(null, "Client configuration changed after planning the update");
	}

	/** The selection gate a fresh commit and a not-yet-published recovery both run before anything moves. */
	void verifySelectionUnchanged(UpdateTransaction transaction) throws IOException {
		if (validator.mutableInputDrift(transaction, fileCache).selection()) throw new IOException("Group selection changed after planning for modpack " + transaction.plan().modpackId());
	}

	/** The gate after the live operations: both the selection and the configuration the plan assumed must still hold. */
	void afterLiveOperations(UpdateTransaction transaction) throws IOException {
		MutableInputDrift applied = validator.mutableInputDrift(transaction, fileCache);
		if (applied.selection()) throw new UpdateReplanRequiredException(null, "Group selection changed while applying the update");
		if (applied.configuration()) throw new UpdateReplanRequiredException(null, "Client configuration changed while applying the update");
	}

	boolean configurationChanged(UpdateTransaction transaction) throws IOException {
		return validator.mutableInputDrift(transaction, fileCache).configuration();
	}

	/** The last gate before durable finalization: no mutable input may have moved during publication either. */
	void beforeFinalization(UpdateTransaction transaction) throws IOException {
		MutableInputDrift finalized = validator.mutableInputDrift(transaction, fileCache);
		if (finalized.selection() || finalized.configuration()) throw new UpdateReplanRequiredException(null, "Mutable client configuration changed before update finalization");
	}

	/** The overlay half of the recovery question: the live overlay tombstones must still equal the plan's projected overlay rows. */
	private boolean overlayStateMatches(UpdateTransaction transaction) throws IOException {
		Map<String, UpdatePlan.FileState> expected = new TreeMap<>();
		for (ProjectedFile projected : transaction.plan().projectedFinalState()) {
			if (projected.root() != Root.OVERLAY) continue;
			expected.put(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath()), projected.present()
					? new UpdatePlan.FileState(projected.expectedHash(), projected.expectedSize(), true)
					: new UpdatePlan.FileState(null, -1, false));
		}
		return expected.equals(ClientOverlaySnapshot.capture(storage, transaction.plan().modpackId(), fileCache).files());
	}
}
