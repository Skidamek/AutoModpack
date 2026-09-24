package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.BaselineCapture;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.ConflictAction;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.PlatformUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.WindowsLockProbe;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** Validates and applies the one journaled client operation plan. */
public final class UpdateTransactionExecutor {
	private final Context context;
	private final UpdateTransactionValidator validator;
	private FileCache fileCache;

	@FunctionalInterface
	public interface CommitAction {
		void run(UpdateTransaction transaction, ModpackJsons.ModpackContentFields target) throws IOException;
	}

	/** One apply of the current plan through the executor. */
	@FunctionalInterface
	public interface CommitCall {
		Execution run() throws IOException;
	}

	/** The replan policy: rebuilds the plan from mutable inputs, rechecks it against the review, and commits it once. */
	@FunctionalInterface
	public interface ReplanCall {
		Execution run(Execution failedExecution) throws IOException;
	}

	public record Context(ClientStorage storage, CommitAction beforeManifestAction) {
		public Context {
			storage = Objects.requireNonNull(storage, "storage");
		}
	}

	/**
	 * One apply outcome. {@code held} is the lock probe's receipt for a {@code DEFERRED_LOCKED} result: which
	 * paths stayed held open and by which processes, when Windows could answer; null for every other status.
	 */
	public record Execution(UpdateTransaction.Status status, UpdateTransaction transaction, String operation, Path blockedPath, String message, String held) {
		public boolean success() {
			return status == UpdateTransaction.Status.SUCCESS;
		}

		public boolean replanRequired() {
			return status == UpdateTransaction.Status.REPLAN_REQUIRED;
		}
	}

	public UpdateTransactionExecutor(Context context) {
		this.context = Objects.requireNonNull(context);
		validator = new UpdateTransactionValidator(context.storage());
	}

	public Execution commit(UpdatePlan plan, SelectedModpackTarget target, String overlayDigest,
			ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) throws IOException {
		UpdateTransaction transaction = UpdateTransaction.create(plan, target, overlayDigest, expectedClientConfig);
		return commitPrepared(transaction, target);
	}

	public Execution commit(UpdateTransaction transaction) throws IOException {
		return commitPrepared(transaction, null);
	}

	/** Commits an already-built transaction against the target it was created for; the session builds transactions to decorate them with their state-history story. */
	public Execution commit(UpdateTransaction transaction, SelectedModpackTarget unpublishedTarget) throws IOException {
		return commitPrepared(transaction, unpublishedTarget);
	}

	/**
	 * The one commit-with-replan: applies the current plan once, and on a replan-required result rebuilds the plan from
	 * mutable inputs through {@code replan} and retries exactly once. A second replan-required result is terminal and
	 * rethrows the underlying replan reason; a caller whose terminal spelling differs throws it from its replan call.
	 */
	public Execution commitWithReplan(CommitCall apply, ReplanCall replan) throws IOException {
		Execution execution = apply.run();
		if (!execution.replanRequired()) return execution;
		// The first attempt's reason is the receipt for why a replan is happening at all; never let the retry swallow it.
		LOGGER.warn("The update apply needs a replan: {}; rebuilding the plan from mutable inputs", execution.message());
		execution = replan.run(execution);
		if (execution.replanRequired()) throw new UpdateReplanRequiredException(execution.blockedPath(), execution.message());
		return execution;
	}

	private Execution commitPrepared(UpdateTransaction transaction, SelectedModpackTarget unpublishedTarget) throws IOException {
		return ClientStorageMutation.run(context.storage(), () -> commitPreparedLocked(transaction, unpublishedTarget));
	}

	private Execution commitPreparedLocked(UpdateTransaction transaction, SelectedModpackTarget unpublishedTarget) throws IOException {
		return withFileCache(cache -> {
			validator.validate(transaction, unpublishedTarget, true, cache);
			validateSelectionBeforeMutation(transaction);
			preparePendingReplacement();
			ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
			// Receipt before the first mutation: other processes sharing the store see this instance only through
			// its ownership receipt, so the journaled plan's objects must be pinned by name before apply runs.
			ClientObjectStore.publishOwnership(context.storage());
			return executePersisted(transaction);
		});
	}

	private void preparePendingReplacement() throws IOException {
		ClientStorage storage = context.storage();
		if (Files.exists(storage.repairJournalFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("An offline repair must finish before an update can start");
		UpdateTransaction pending = UpdateTransaction.read(storage.transactionFile());
		if (pending == null) {
			sweepUnpinnedPublicationDirectories(storage);
			return;
		}
		if (pending.phase == UpdateTransaction.Phase.COMMITTED) {
			// A crash can land after the COMMITTED marker but before its state-history checkpoint; replaying the tail here
			// records the entry (idempotent by transaction id) before the record that produced it retires.
			cleanupTransactionDirectories(pending);
			recordStateHistory(pending);
			Files.deleteIfExists(storage.transactionFile());
			return;
		}
		validator.validatePendingReplacementEnvelope(pending);
		if (Files.exists(storage.backupDirectory(), LinkOption.NOFOLLOW_LINKS)
				&& !verifyProjectionQuietly(storage.activeDirectory(), pending.plan().projectedFinalState()))
			throw new IOException("A deferred projection publication must finish before its request can be replaced");
		cleanupTransactionDirectories(pending);
	}

	/** Recovers the current mailbox contents, never a transaction captured by an earlier process. */
	public Execution recoverLatest() throws IOException {
		return ClientStorageMutation.run(context.storage(), () -> recoverPersisted(null));
	}

	/**
	 * The stuck-update escape: drop in-flight publication, keep the last finalized generation, and retire the journal.
	 * {@code backup/} is the previous committed tree only while finalize has not yet written {@code active-state.json}.
	 */
	public Path abandonStuckPublication(UpdateTransaction transaction) throws IOException {
		Objects.requireNonNull(transaction, "transaction");
		return ClientStorageMutation.run(context.storage(), () -> abandonStuckPublicationPersisted(transaction));
	}

	private Path abandonStuckPublicationPersisted(UpdateTransaction transaction) throws IOException {
		ClientStorage storage = context.storage();
		Path active = storage.activeDirectory();
		Path backup = storage.backupDirectory();
		FileTrees.delete(storage.incomingDirectory());
		if (!generationAlreadyFinalized(transaction) && Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) {
			FileTrees.delete(active);
			FileTrees.moveRecoverableDirectory(backup, active);
		} else {
			FileTrees.delete(backup);
		}
		Path stuckJournal = storage.clientDirectory().resolve("update-transaction.stuck-" + UUID.randomUUID() + ".json");
		Files.move(storage.transactionFile(), stuckJournal, StandardCopyOption.REPLACE_EXISTING);
		return stuckJournal;
	}

	private boolean generationAlreadyFinalized(UpdateTransaction transaction) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = context.storage().readActiveState();
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL || transaction.purpose == UpdateTransaction.Purpose.MODPACK_DEACTIVATION) return state == null;
		return state != null && transaction.plan().packTarget().contentToken() != null && transaction.plan().packTarget().contentToken().equals(state.contentToken);
	}

	/** Reports mutable input drift that requires a fresh plan before live mutation can continue. */
	public boolean hasMutableInputDrift(UpdateTransaction transaction) throws IOException {
		return withFileCache(cache -> {
			if (transaction == null) return false;
			UpdateTransactionValidator.MutableInputDrift drift = validator.mutableInputDrift(transaction);
			if (projectionPublicationStarted(transaction))
				return drift.configuration() || transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
						&& (!overlayStateMatches(transaction) || drift.selection());
			return drift.any();
		});
	}

	/** Reports whether the live state has already reached the point where only projection publication remains. */
	private boolean projectionPublicationStarted(UpdateTransaction transaction) {
		return ClientProjectionView.publicationStarted(context.storage(), transaction);
	}

	private Execution recoverPersisted(String expectedTransactionId) throws IOException {
		return withFileCache(cache -> {
			UpdateTransaction pending = UpdateTransaction.read(context.storage().transactionFile());
			if (pending == null) return new Execution(UpdateTransaction.Status.SUCCESS, null, null, null, null, null);
			if (expectedTransactionId != null && !expectedTransactionId.equals(pending.transactionId))
				throw new IOException("The requested update transaction was superseded by a newer pending request");
			boolean publicationStarted = projectionPublicationStarted(pending);
			if (!publicationStarted && hasMutableInputDrift(pending)) throw new UpdateReplanRequiredException(null, "Pending update input changed after planning");
			validator.validateUnchecked(pending, null, !publicationStarted, fileCache);
			if (!publicationStarted) validateSelectionBeforeMutation(pending);
			return executePersisted(pending);
		});
	}

	private Execution executePersisted(UpdateTransaction transaction) throws IOException {
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE)
			// The copies' ownership record is rewritten before every apply run, recovery replays included, so a crash
			// mid-apply never leaves installed copies unowned; a doc of a run that never applied is inert until it does.
			GeneratedCopyState.fromCopies(transaction.plan().modpackId(), transaction.plan().packTarget().contentToken(), transaction.selectionDigest(), transaction.plan().generatedCopies()).write(context.storage());
		if (transaction.phase == UpdateTransaction.Phase.COMMITTED) return finalizeCommitted(transaction);
		AtomicReference<Operation> current = new AtomicReference<>();
		Path blockedPath = null;
		boolean publicationStarted = projectionPublicationStarted(transaction);
		boolean liveAlreadyApplied = transaction != null && (publicationStarted || managedStateMatches(transaction));
		boolean preserveNewerSelection = publicationStarted && validator.mutableInputDrift(transaction).selection();
		try {
			transaction.resultStatus = null;
			transaction.resultOperation = null;
			transaction.resultPath = null;
			transaction.resultMessage = null;
			setPhase(transaction, UpdateTransaction.Phase.PREPARING);
			applyModpackTransaction(transaction, current, publicationStarted, liveAlreadyApplied, preserveNewerSelection);
			persistPhase(transaction, UpdateTransaction.Phase.COMMITTED);
			return finalizeCommitted(transaction);
		} catch (IOException e) {
			Operation currentOperation = current.get();
			if (blockedPath == null && currentOperation != null) blockedPath = resolve(currentOperation, transaction);
			String operationName = currentOperation == null ? null : currentOperation.operation().name();
			if (e instanceof UpdateReplanRequiredException replan && blockedPath == null) blockedPath = replan.changedPath();
			UpdateTransaction.Status status = e instanceof UpdateReplanRequiredException
					? UpdateTransaction.Status.REPLAN_REQUIRED
					: isLockFailure(e) ? UpdateTransaction.Status.DEFERRED_LOCKED : UpdateTransaction.Status.FAILED;
			if (status != UpdateTransaction.Status.FAILED) {
				transaction.phase = UpdateTransaction.Phase.DEFERRED;
				// The receipt travels in the journal message, so every later boot re-announces who ate the update.
				String held = status == UpdateTransaction.Status.DEFERRED_LOCKED ? WindowsLockProbe.describeHeld(blockedPath != null ? blockedPath : context.storage().activeDirectory()) : null;
				if (held != null) LOGGER.warn("The update is blocked by paths held open by other processes: {}", held);
				String message = held == null ? e.getMessage() : e.getMessage() + " (held: " + held + ")";
				recordResult(transaction, status, operationName, blockedPath, message, e);
				return new Execution(status, transaction, operationName, blockedPath, message, held);
			}
			recordResult(transaction, UpdateTransaction.Status.FAILED, operationName, blockedPath, e.getMessage(), e);
			throw new UpdateExecutionException(operationName, blockedPath, e);
		}
	}

	/**
	 * The one durable commit tail, shared by the live path and the recovery of a crash after the COMMITTED phase
	 * persisted: drop the working directories, checkpoint the state history, retire the journal, and refresh the
	 * ownership receipt. The receipt published at commit start names every object the journal pinned, and retiring it
	 * is the only reference loss since, so the stale on-disk receipt protected everything until this refresh replaces
	 * it with exactly the durable set. A publish is a full-state sweep (~2ms over a 200-entry journal, pinned by
	 * {@code ClientObjectStoreTest}), so a commit publishes at its start and here, and never per file.
	 */
	private Execution finalizeCommitted(UpdateTransaction transaction) throws IOException {
		cleanupTransactionDirectories(transaction);
		recordStateHistory(transaction);
		Files.deleteIfExists(context.storage().transactionFile());
		ClientObjectStore.publishOwnership(context.storage());
		return new Execution(UpdateTransaction.Status.SUCCESS, transaction, null, null, null, null);
	}

	/**
	 * The instance state history entry of a committed transaction: one complete checkpoint of every tracked file plus
	 * the changes and before-state captures that produced it. The append lands before the transaction record retires
	 * and dedupes on the transaction id, so a crash between append and retirement replays to the same single entry,
	 * and the checkpoint is always durable before the record that produced it can be forgotten.
	 */
	private void recordStateHistory(UpdateTransaction transaction) throws IOException {
		StateHistory.snapshotIfDirty(context.storage(), StateHistory.planPaths(transaction.plan()), snapshotKind(transaction), transaction.plan().modpackId(), transaction.transactionId);
	}

	private void snapshotBefore(UpdateTransaction transaction) throws IOException {
		StateHistory.snapshotIfDirty(context.storage(), StateHistory.planPaths(transaction.plan()), ClientStateJournal.Kind.LIVE, transaction.plan().modpackId(), transaction.transactionId,
				transaction.plan());
	}

	private ClientStateJournal.Kind snapshotKind(UpdateTransaction transaction) throws IOException {
		ClientStateJournal.Kind declared = ClientStateJournal.Kind.parseDeclared(transaction.stateKind);
		if (declared != null) return declared;
		return switch (transaction.purpose) {
			case MODPACK_UPDATE -> {
				String modpackId = transaction.plan().modpackId();
				boolean seen = ClientStateJournal.open(context.storage()).entries().stream()
						.anyMatch(entry -> entry.modpackId().equals(modpackId) && (entry.kind() == ClientStateJournal.Kind.INSTALL || entry.kind() == ClientStateJournal.Kind.UPDATE));
				yield seen ? ClientStateJournal.Kind.UPDATE : ClientStateJournal.Kind.INSTALL;
			}
			case MODPACK_DEACTIVATION -> ClientStateJournal.Kind.DEACTIVATION;
			case MODPACK_REMOVAL -> ClientStateJournal.Kind.REMOVAL;
		};
	}

	/** The modpack apply sequence: pre-mutation captures, live operations, projection publication, and durable finalization. */
	private void applyModpackTransaction(UpdateTransaction transaction, AtomicReference<Operation> current, boolean publicationStarted, boolean liveAlreadyApplied,
			boolean preserveNewerSelection) throws IOException {
		if (!publicationStarted && validator.mutableInputDrift(transaction).configuration())
			throw new UpdateReplanRequiredException(null, "Client configuration changed after planning the update");
		snapshotBefore(transaction);
		capturePreStates(transaction);
		capturePreservations(transaction);
		captureConflicts(transaction);
		if (!liveAlreadyApplied) applyOperations(transaction, current);
		current.set(null);
		if (!publicationStarted) {
			verifyManagedFinalState(transaction);
			UpdateTransactionValidator.MutableInputDrift applied = validator.mutableInputDrift(transaction);
			if (applied.selection()) throw new UpdateReplanRequiredException(null, "Group selection changed while applying the update");
			if (applied.configuration()) throw new UpdateReplanRequiredException(null, "Client configuration changed while applying the update");
		}
		publishProjection(transaction);
		if (publicationStarted
				&& (!managedStateMatches(transaction) || preserveNewerSelection || validator.mutableInputDrift(transaction).configuration()))
			throw new UpdateReplanRequiredException(null, "Mutable client state changed while publishing the update");
		UpdateTransactionValidator.MutableInputDrift finalized = validator.mutableInputDrift(transaction);
		if (finalized.selection() || finalized.configuration())
			throw new UpdateReplanRequiredException(null, "Mutable client configuration changed before update finalization");
		finalizeModpackState(transaction, preserveNewerSelection);
		claimSelection(transaction);
	}

	/** Builds and swaps the incoming projection unless the active tree already matches; no-ops when the projection was published earlier. */
	private void publishProjection(UpdateTransaction transaction) throws IOException {
		if (verifyProjectionQuietly(context.storage().activeDirectory(), transaction.plan().projectedFinalState())) return;
		buildIncomingProjection(transaction);
		setPhase(transaction, UpdateTransaction.Phase.SWAPPING);
		swapProjection(transaction);
	}

	/** The durable finalization: planned config, pack state, active-state pointer, and the before-manifest hook. */
	private void finalizeModpackState(UpdateTransaction transaction, boolean preserveNewerSelection) throws IOException {
		SelectedModpackTarget resolved = validator.resolvedTarget(transaction, validator.targetDocument(transaction));
		if (transaction.plan().plannedClientConfig() != null && !preserveNewerSelection)
			ConfigTools.writeAtomic(context.storage().clientConfigFile(), transaction.plan().plannedClientConfig());
		if (context.beforeManifestAction() != null && transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE)
			context.beforeManifestAction().run(transaction, resolved.flatTarget());
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			context.storage().writeActiveState(transaction.plan().modpackId(), transaction.packTarget().contentToken(), resolved.document().ownershipLedger().toFields());
		} else if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
			FileTrees.delete(context.storage().generatedCopiesGenerationDirectory(transaction.plan().modpackId(), transaction.plan().packTarget().contentToken()));
			context.storage().clearActiveState();
		} else {
			context.storage().clearActiveState();
		}
	}

	/** Persists the terminal result fields of an interrupted transaction onto its journal record. */
	private void recordResult(UpdateTransaction transaction, UpdateTransaction.Status status, String operationName, Path blockedPath, String message, IOException cause)
			throws IOException {
		transaction.resultStatus = status;
		transaction.resultOperation = operationName;
		transaction.resultPath = blockedPath == null ? null : blockedPath.toString();
		transaction.resultMessage = message;
		try {
			ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
		} catch (IOException journalFailure) {
			cause.addSuppressed(journalFailure);
		}
	}

	/** Marks an in-memory phase transition. The phase is only durable at the boundaries: the PLANNED intent, the COMMITTED marker, and the result receipt. */
	private void setPhase(UpdateTransaction transaction, UpdateTransaction.Phase phase) {
		transaction.phase = phase;
	}

	/** Persists the journal at a durable phase boundary, rewriting the whole record exactly where recovery depends on the phase. */
	private void persistPhase(UpdateTransaction transaction, UpdateTransaction.Phase phase) throws IOException {
		setPhase(transaction, phase);
		ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
	}

	private void applyOperations(UpdateTransaction transaction, AtomicReference<Operation> current) throws IOException {
		for (Operation operation : transaction.plan().operations()) {
			if (operation.operation() != OperationType.INSTALL_OBJECT || operation.root() == Root.PROJECTION) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (FileIntegrity.matchesNamed(target, operation.expectedSize(), operation.expectedObjectHash(), fileCache)) continue;
			verifyExpectedExisting(operation, target);
			Path source = context.storage().objectFile(operation.expectedObjectHash());
			VerifiedFileTransfer.copyAtomic(source, target, operation.expectedSize(), operation.expectedObjectHash(), fileCache);
		}
		for (Operation operation : transaction.plan().operations()) {
			if (operation.operation() != OperationType.DELETE || operation.root() == Root.PROJECTION) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				verifyExpectedExisting(operation, target);
				Files.delete(target);
			}
			FileTrees.pruneEmptyAncestors(target, context.storage().root(operation.root(), transaction.plan().modpackId()));
		}
	}

	private void verifyExpectedExisting(Operation operation, Path target) throws IOException {
		if (operation.root() != Root.OVERLAY && operation.root() != Root.GAME_DIR) return;
		String described = operation.root() == Root.OVERLAY ? "Client overlay" : "Game-directory";
		if (operation.expectedExistingHash() == null) {
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new UpdateReplanRequiredException(target, described + " target appeared after planning: " + target);
			return;
		}
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
				|| !FileIntegrity.matches(target, Files.size(target), operation.expectedExistingHash(), fileCache))
			throw new UpdateReplanRequiredException(target, described + " target changed after planning: " + target);
	}

	/** Acquires every planned preservation's bytes before mutation, so the state entry's change hashes name real objects. */
	private void capturePreservations(UpdateTransaction transaction) throws IOException {
		for (Preservation preservation : transaction.plan().preservations()) {
			Path source = resolve(preservation.root(), preservation.relativePath(), transaction);
			if (!FileIntegrity.matches(source, preservation.expectedSize(), preservation.expectedHash(), fileCache))
				throw new IOException("Preserved source changed: " + source);
			Path object = context.storage().objectFile(preservation.expectedHash().toLowerCase(Locale.ROOT));
			VerifiedFileTransfer.copyAtomicImmutable(source, object, preservation.expectedSize(), preservation.expectedHash(), fileCache);
		}
	}

	/** Acquires the local side of every preserve-local conflict before the pack's version overwrites it. */
	private void captureConflicts(UpdateTransaction transaction) throws IOException {
		for (Conflict conflict : transaction.plan().conflicts()) {
			if (conflict.action() != ConflictAction.PRESERVE_LOCAL) continue;
			Path source = context.storage().gamePath(conflict.sourcePath());
			if (!FileIntegrity.matches(source, conflict.sourceSize(), conflict.sourceHash(), fileCache))
				throw new IOException("Conflict source changed: " + source);
			Path object = context.storage().objectFile(conflict.sourceHash().toLowerCase(Locale.ROOT));
			VerifiedFileTransfer.copyAtomicImmutable(source, object, conflict.sourceSize(), conflict.sourceHash(), fileCache);
		}
	}

	private void verifyManagedFinalState(UpdateTransaction transaction) throws IOException {
		for (ProjectedFile projected : transaction.plan().projectedFinalState()) {
			if (projected.root() == Root.PROJECTION) continue;
			Path target = resolve(projected.root(), projected.relativePath(), transaction);
			if (projected.present()) {
				if (!FileIntegrity.matchesNamed(target, projected.expectedSize(), projected.expectedHash(), fileCache))
					throw new UpdateReplanRequiredException(target, "Projected target changed during update: " + target);
			} else
				if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
					throw new UpdateReplanRequiredException(target, "Projected absent target appeared during update: " + target);
		}
	}

	private boolean managedStateMatches(UpdateTransaction transaction) {
		try {
			verifyManagedFinalState(transaction);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private boolean overlayStateMatches(UpdateTransaction transaction) throws IOException {
		Map<String, UpdatePlan.FileState> expected = new TreeMap<>();
		for (ProjectedFile projected : transaction.plan().projectedFinalState()) {
			if (projected.root() != Root.OVERLAY) continue;
			expected.put(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath()), projected.present()
					? new UpdatePlan.FileState(projected.expectedHash(), projected.expectedSize(), true)
					: new UpdatePlan.FileState(null, -1, false));
		}
		return expected.equals(ClientOverlaySnapshot.capture(context.storage(), transaction.plan().modpackId(), fileCache).files());
	}

	private void buildIncomingProjection(UpdateTransaction transaction) throws IOException {
		Path incoming = context.storage().incomingDirectory();
		FileTrees.delete(incoming);
		Files.createDirectories(incoming);
		for (ProjectedFile projected : transaction.plan().projectedFinalState()) {
			if (projected.root() != Root.PROJECTION || !projected.present()) continue;
			Path source = context.storage().objectFile(projected.expectedHash());
			Path target = incoming.resolve(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath())).normalize();
			if (!target.startsWith(incoming)) throw new IOException("Projection path escapes incoming directory");
			VerifiedFileTransfer.linkAtomic(source, target, projected.expectedSize(), projected.expectedHash(), fileCache);
		}
		verifyProjection(incoming, transaction.plan().projectedFinalState());
	}

	private void swapProjection(UpdateTransaction transaction) throws IOException {
		Path active = context.storage().activeDirectory();
		Path incoming = context.storage().incomingDirectory();
		Path backup = context.storage().backupDirectory();
		if (verifyProjectionQuietly(active, transaction.plan().projectedFinalState())) {
			FileTrees.delete(incoming);
			FileTrees.delete(backup);
			return;
		}
		if (!verifyProjectionQuietly(incoming, transaction.plan().projectedFinalState())) buildIncomingProjection(transaction);
		if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
			FileTrees.delete(active);
		} else if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
			FileTrees.moveRecoverableDirectory(active, backup);
		}
		FileTrees.moveRecoverableDirectory(incoming, active);
		verifyProjection(active, transaction.plan().projectedFinalState());
	}

	private void verifyProjection(Path projection, List<ProjectedFile> finalState) throws IOException {
		Map<String, ProjectedFile> expected = new HashMap<>();
		for (ProjectedFile projected : finalState) if (projected.root() == Root.PROJECTION && projected.present()) expected.put(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath()), projected);
		if (!Files.isDirectory(projection, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Active client projection is not a directory: " + projection);
		try (var paths = Files.walk(projection)) {
			for (Path path : paths.filter(candidate -> !candidate.equals(projection)).toList()) {
				if (Files.isSymbolicLink(path)) throw new IOException("Client projection contains a symbolic link: " + path);
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				String relative = UpdateTransactionValidator.normalizeOperationPath(projection.relativize(path).toString());
				ProjectedFile expectedFile = expected.remove(relative);
				if (expectedFile == null || !FileIntegrity.matchesObject(path, context.storage().objectFile(expectedFile.expectedHash()), expectedFile.expectedSize(), expectedFile.expectedHash(), fileCache))
					throw new IOException("Client projection file verification failed: " + path);
			}
		}
		if (!expected.isEmpty()) throw new IOException("Client projection is missing files: " + expected.keySet());
	}

	private boolean verifyProjectionQuietly(Path projection, List<ProjectedFile> finalState) {
		try {
			verifyProjection(projection, finalState);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private void cleanupTransactionDirectories(UpdateTransaction transaction) throws IOException {
		FileTrees.delete(context.storage().incomingDirectory());
		FileTrees.delete(context.storage().backupDirectory());
	}

	/**
	 * With no usable pending transaction the publication directories are provably unpinned - no journal owns their
	 * bytes - so leftovers of a crash whose journal was lost or set aside are swept here. Left alone they read as a
	 * publication already started ({@code ClientProjectionView.publicationStarted}), which would make the next
	 * update silently skip every live operation and its final-state verification while its commit reports success.
	 */
	public static void sweepUnpinnedPublicationDirectories(ClientStorage storage) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			FileTrees.delete(storage.incomingDirectory());
			FileTrees.delete(storage.backupDirectory());
			return null;
		});
	}

	/** Verifies every planned capture against the live file and acquires its bytes into the object store; the entry's captures then pin them. */
	private void capturePreStates(UpdateTransaction transaction) throws IOException {
		if (transaction.plan().baselineCaptures().isEmpty()) return;
		for (BaselineCapture capture : transaction.plan().baselineCaptures()) {
			Path source = resolve(capture.root(), capture.relativePath(), transaction);
			if (capture.absent()) {
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Captured path was expected to be absent: " + source);
				continue;
			}
			if (!FileIntegrity.matches(source, capture.expectedSize(), capture.expectedHash(), fileCache)) throw new IOException("Captured source changed: " + source);
			Path object = context.storage().objectFile(capture.expectedHash());
			VerifiedFileTransfer.copyAtomicImmutable(source, object, capture.expectedSize(), capture.expectedHash(), fileCache);
		}
	}

	private void claimSelection(UpdateTransaction transaction) throws IOException {
		ClientSelectionStore selections = new ClientSelectionStore(context.storage().selectionFile());
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) selections.compareAndSet(transaction.plan().modpackId(), transaction.expectedPriorIntent(), transaction.targetIntent());
		else if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) selections.remove(transaction.plan().modpackId(), transaction.expectedPriorIntent());
	}

	private void validateSelectionBeforeMutation(UpdateTransaction transaction) throws IOException {
		if (validator.mutableInputDrift(transaction).selection()) throw new IOException("Group selection changed after planning for modpack " + transaction.plan().modpackId());
	}

	private Path resolve(Operation operation, UpdateTransaction transaction) throws IOException {
		return resolve(operation.root(), operation.relativePath(), transaction);
	}

	private Path resolve(Root root, String relativePath, UpdateTransaction transaction) throws IOException {
		return FileTrees.resolveConfined(context.storage().root(root, transaction.plan().modpackId()), UpdateTransactionValidator.normalizeOperationPath(relativePath), "Operation target");
	}

	private interface FileCacheWork<T> {
		T run(FileCache cache) throws IOException;
	}

	private <T> T withFileCache(FileCacheWork<T> work) throws IOException {
		if (fileCache != null) return work.run(fileCache);
		try (FileCache cache = FileCache.open(context.storage().fileCacheDirectory())) {
			fileCache = cache;
			try {
				return work.run(cache);
			} finally {
				fileCache = null;
			}
		}
	}

	public static boolean isLockFailure(IOException exception) {
		return isLockFailure(exception, PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS);
	}

	static boolean isLockFailure(IOException exception, boolean windows) {
		Throwable current = exception;
		while (current != null) {
			// Windows reports an open handle that denies delete sharing as AccessDeniedException, without a lock-specific
			// reason. On other kernels the same exception is a plain permission problem - a permanent failure, not an
			// update that should defer forever with a locked-file story - so only the explicit lock-worded messages count.
			if (windows && current instanceof AccessDeniedException) return true;
			if (current instanceof FileSystemException fileSystemException) {
				String detail = (Objects.toString(fileSystemException.getReason(), "") + " " + Objects.toString(fileSystemException.getMessage(), "")).toLowerCase(Locale.ROOT);
				if (detail.contains("used by another process") || detail.contains("being used by another process") || detail.contains("sharing violation")) return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
