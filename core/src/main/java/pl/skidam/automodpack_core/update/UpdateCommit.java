package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ReconfConfigs;
import pl.skidam.automodpack_core.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.BaselineCapture;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.ConflictAction;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.PlatformUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.WindowsLockProbe;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The one durable client commit module: applies a reviewed plan, recovers the persisted journal, or defers it on
 * locked files. Every apply - fresh, recovered, rebuilt - goes through this door, so the durability ordering
 * (transaction persisted and ownership receipt published before the first mutation), the replan budget, and the
 * defer receipt are facts of this module instead of choreography each caller re-derives.
 *
 * <p>
 * The replan budget: a rebuild at a drift trigger gets one apply, and that apply's own replan-required result is
 * terminal. Callers supply the rebuild as {@link Replan} - the rebuild is policy (the session gates it on the
 * player's review, boot gates it on outcome compatibility), the budget never is. A null {@link Replan} makes every
 * drift terminal, which is the helper process's spelling.
 *
 * <p>
 * Retry and restart policy for a deferred transaction belongs to the caller's recovery module, never here:
 * {@link #run} reports the deferral with the lock probe's receipt already journaled, and reports a terminal replan
 * as data instead of an exception so policy code never needs a catch to route it.
 */
public final class UpdateCommit {
	private final ClientStorage storage;
	private final UpdateTransactionValidator validator;
	private FileCache fileCache;
	/** One-run flag: a recovery rebuild already refused or exhausted its budget, so no second rebuild may follow. */
	private boolean terminalReplan;

	/** A transaction to apply now. {@code unpublishedTarget} names the target document when it is not yet in the generation store; null otherwise. */
	public record Built(UpdateTransaction transaction, SelectedModpackTarget unpublishedTarget) {
		public static Built of(UpdateTransaction transaction) {
			return new Built(transaction, null);
		}
	}

	/** Supplies the transaction to apply. Called once per apply attempt, so per-attempt object pinning rides it. */
	@FunctionalInterface
	public interface TransactionSource {
		Built build() throws IOException;
	}

	/** Why a rebuild is being asked for: the transaction the attempt ended with, and its failure facts. Never a null transaction. */
	public record Cause(UpdateTransaction transaction, String operation, Path blockedPath, String message) {}

	/** Rebuilds the plan from mutable inputs; the caller owns the review or compatibility gate the rebuild must pass. */
	@FunctionalInterface
	public interface Replan {
		Built rebuild(Cause cause) throws IOException;
	}

	/** One apply request: a fresh transaction, or a replay of the persisted journal. */
	public sealed interface Attempt permits Fresh, Recover {}
	public record Fresh(TransactionSource source, Replan rebuild) implements Attempt {}
	public record Recover(Replan rebuild) implements Attempt {}

	/** Nothing was pending; for recovery policy this is the same as applied - there is nothing left to do. */
	public record Idle() implements Outcome {
		public UpdateTransaction.Status status() {
			return UpdateTransaction.Status.SUCCESS;
		}
	}
	public record Applied(UpdateTransaction transaction) implements Outcome {
		public UpdateTransaction.Status status() {
			return UpdateTransaction.Status.SUCCESS;
		}
	}
	public record Deferred(UpdateTransaction transaction, String operation, Path blockedPath, String message, String held) implements Outcome, Blocked {
		public UpdateTransaction.Status status() {
			return UpdateTransaction.Status.DEFERRED_LOCKED;
		}
	}
	public record ReplanRequired(UpdateTransaction transaction, String operation, Path blockedPath, String message) implements Outcome, Blocked {
		public UpdateTransaction.Status status() {
			return UpdateTransaction.Status.REPLAN_REQUIRED;
		}
	}

	/** The transaction an attempt ended with and the facts of why it did not apply, shared by both blocked outcomes. */
	public sealed interface Blocked permits Deferred, ReplanRequired {
		UpdateTransaction transaction();

		String operation();

		Path blockedPath();

		String message();

		UpdateTransaction.Status status();
	}

	public sealed interface Outcome permits Applied, Deferred, ReplanRequired, Idle {
		/** The journal status spelling of this outcome, for the receipts logs and policy code already speak. */
		UpdateTransaction.Status status();
	}

	public UpdateCommit(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage, "storage");
		this.validator = new UpdateTransactionValidator(storage);
	}

	/**
	 * The one door. Holds the client-store mutation lock for each apply attempt; a rebuild runs between attempts, outside it.
	 * Throws {@link UpdateExecutionException} after journaling a FAILED receipt, {@link UpdateReplanRequiredException}
	 * only when a fresh apply exhausts its rebuild, and {@link IOException} for physical trouble; every blocked or
	 * replan-required recovery is returned as data.
	 */
	public Outcome run(Attempt attempt) throws IOException {
		if (attempt instanceof Fresh fresh) return runFresh(fresh);
		if (attempt instanceof Recover recover) return runRecovered(recover);
		throw new IllegalArgumentException("Unknown attempt kind: " + attempt);
	}

	/** The reviewed-commit door: applies the plan, rebuilds once on drift, and returns the plan that durably applied. */
	public UpdatePlan commit(TransactionSource source, Replan rebuild) throws IOException {
		Outcome outcome = run(new Fresh(source, rebuild));
		if (outcome instanceof Applied applied) return applied.transaction().plan();
		if (outcome instanceof ReplanRequired replan) throw new UpdateReplanRequiredException(replan.blockedPath(), replan.message());
		if (outcome instanceof Deferred deferred) throw new UpdateDeferredException(deferred.transaction().transactionId, deferred.blockedPath(), deferred.message());
		throw new IllegalStateException("A fresh commit cannot be idle");
	}

	private Outcome runFresh(Fresh fresh) throws IOException {
		Outcome outcome = apply(fresh.source().build());
		if (!(outcome instanceof ReplanRequired replan)) return outcome;
		if (fresh.rebuild() == null) return outcome;
		LOGGER.warn("The update apply needs a replan: {}; rebuilding the plan from mutable inputs", replan.message());
		// The first attempt's reason is the receipt for why a replan is happening at all; never let the retry swallow it.
		outcome = apply(fresh.rebuild().rebuild(new Cause(replan.transaction(), replan.operation(), replan.blockedPath(), replan.message())));
		if (outcome instanceof ReplanRequired terminal) throw new UpdateReplanRequiredException(terminal.blockedPath(), terminal.message());
		return outcome;
	}

	private Outcome runRecovered(Recover recover) throws IOException {
		UpdateTransaction pending = UpdateTransaction.read(storage.transactionFile());
		if (pending == null) return new Idle();
		terminalReplan = false;
		return withFileCache(cache -> {
			Outcome outcome = recoveryAttempt(pending, recover.rebuild());
			if (terminalReplan || !(outcome instanceof ReplanRequired replan) || recover.rebuild() == null) return outcome;
			LOGGER.warn("The recovered update needs a replan: {}; rebuilding the plan from mutable inputs", replan.message());
			outcome = applyRebuilt(recover.rebuild(), new Cause(replan.transaction(), replan.operation(), replan.blockedPath(), replan.message()));
			if (outcome instanceof ReplanRequired) return new ReplanRequired(replan.transaction(), replan.operation(), replan.blockedPath(), "Pending update still requires a fresh plan");
			return outcome;
		});
	}

	/**
	 * One recovery pass against the persisted journal: replay it, rebuilding from mutable inputs at the drift triggers.
	 * The triggers run unlocked, exactly like boot's advisory pre-checks always did; the authoritative drift re-check
	 * lives inside {@link #executeRecovered} under the mutation lock. A rebuild's own refusal, and a replan-required
	 * result past the post-success rebuild, are terminal here and never reach the second rebuild in {@code runRecovered}.
	 */
	private Outcome recoveryAttempt(UpdateTransaction pending, Replan rebuild) throws IOException {
		if (!publicationStarted(pending) && drift().replanRequired(pending)) {
			if (rebuild == null) return new ReplanRequired(pending, null, null, "Pending update input changed after planning");
			return applyRebuilt(rebuild, new Cause(pending, null, null, "Pending update input changed after planning"));
		}
		Outcome outcome = executeRecovered();
		if (outcome instanceof Applied && rebuild != null && drift().replanRequired(pending)) {
			outcome = applyRebuilt(rebuild, new Cause(pending, null, null, "Pending update changed after the recovery applied it"));
			if (outcome instanceof ReplanRequired) terminalReplan = true;
		}
		return outcome;
	}

	private Outcome applyRebuilt(Replan rebuild, Cause cause) throws IOException {
		try {
			return apply(rebuild.rebuild(cause));
		} catch (UpdateReplanRequiredException e) {
			// The rebuild's own refusal is terminal: the reviewed or compatible outcome no longer holds, and only the
			// next fresh review can re-approve it.
			terminalReplan = true;
			return new ReplanRequired(cause.transaction(), null, e.changedPath(), e.getMessage());
		}
	}

	/** The fresh-apply path: validate, retire or verify whatever was pending, persist this journal, then execute it. */
	private Outcome apply(Built built) throws IOException {
		UpdateTransaction transaction = built.transaction();
		return ClientStorageMutation.run(storage, () -> withFileCache(cache -> {
			validator.validate(transaction, built.unpublishedTarget(), true, cache);
			drift().verifySelectionUnchanged(transaction);
			preparePendingReplacement();
			ConfigTools.writeAtomicCompact(storage.transactionFile(), transaction);
			// Receipt before the first mutation: other processes sharing the store see this instance only through
			// its ownership receipt, so the journaled plan's objects must be pinned by name before apply runs.
			ClientObjectStore.publishOwnership(storage);
			return executeTransaction(transaction);
		}));
	}

	/** The replay path: the persisted journal is already durable, so validation and execution re-read and apply it directly. */
	private Outcome executeRecovered() throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			UpdateTransaction current = UpdateTransaction.read(storage.transactionFile());
			if (current == null) return new Idle();
			boolean publicationStarted = publicationStarted(current);
			if (!publicationStarted && drift().replanRequired(current)) return new ReplanRequired(current, null, null, "Pending update input changed after planning");
			validator.validateUnchecked(current, null, !publicationStarted, fileCache);
			if (!publicationStarted) drift().verifySelectionUnchanged(current);
			return executeTransaction(current);
		});
	}

	private void preparePendingReplacement() throws IOException {
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
				&& !projection().verifyQuietly(storage.activeDirectory(), pending.plan().projectedFinalState()))
			throw new IOException("A deferred projection publication must finish before its request can be replaced");
		cleanupTransactionDirectories(pending);
	}

	private Outcome executeTransaction(UpdateTransaction transaction) throws IOException {
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE)
			// The copies' ownership record is rewritten before every apply run, recovery replays included, so a crash
			// mid-apply never leaves installed copies unowned; a doc of a run that never applied is inert until it does.
			GeneratedCopyState.fromCopies(transaction.plan().modpackId(), transaction.plan().packTarget().contentToken(), transaction.selectionDigest(), transaction.plan().generatedCopies()).write(storage);
		if (transaction.phase == UpdateTransaction.Phase.COMMITTED) return finalizeCommitted(transaction);
		AtomicReference<Operation> current = new AtomicReference<>();
		Path blockedPath = null;
		boolean publicationStarted = publicationStarted(transaction);
		boolean liveAlreadyApplied = publicationStarted || managedStateMatches(transaction);
		boolean preserveNewerSelection = publicationStarted && validator.mutableInputDrift(transaction, fileCache).selection();
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
			if (e instanceof UpdateReplanRequiredException replan && blockedPath == null) blockedPath = replan.changedPath();
			UpdateTransaction.Status status = e instanceof UpdateReplanRequiredException
					? UpdateTransaction.Status.REPLAN_REQUIRED
					: isLockFailure(e) ? UpdateTransaction.Status.DEFERRED_LOCKED : UpdateTransaction.Status.FAILED;
			if (status != UpdateTransaction.Status.FAILED) {
				transaction.phase = UpdateTransaction.Phase.DEFERRED;
				// The receipt travels in the journal message, so every later boot re-announces who ate the update.
				String held = status == UpdateTransaction.Status.DEFERRED_LOCKED ? WindowsLockProbe.describeHeld(blockedPath != null ? blockedPath : storage.activeDirectory()) : null;
				if (held != null) LOGGER.warn("The update is blocked by paths held open by other processes: {}", held);
				String message = held == null ? e.getMessage() : e.getMessage() + " (held: " + held + ")";
				recordResult(transaction, status, currentOperation == null ? null : currentOperation.operation().name(), blockedPath, message, e);
				return status == UpdateTransaction.Status.DEFERRED_LOCKED
						? new Deferred(transaction, currentOperation == null ? null : currentOperation.operation().name(), blockedPath, message, held)
						: new ReplanRequired(transaction, currentOperation == null ? null : currentOperation.operation().name(), blockedPath, message);
			}
			recordResult(transaction, UpdateTransaction.Status.FAILED, currentOperation == null ? null : currentOperation.operation().name(), blockedPath, e.getMessage(), e);
			throw new UpdateExecutionException(currentOperation == null ? null : currentOperation.operation().name(), blockedPath, e);
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
	private Outcome finalizeCommitted(UpdateTransaction transaction) throws IOException {
		cleanupTransactionDirectories(transaction);
		recordStateHistory(transaction);
		Files.deleteIfExists(storage.transactionFile());
		ClientObjectStore.publishOwnership(storage);
		return new Applied(transaction);
	}

	/**
	 * The instance state history entry of a committed transaction: one complete checkpoint of every tracked file plus
	 * the changes and before-state captures that produced it. The append lands before the transaction record retires
	 * and dedupes on the transaction id, so a crash between append and retirement replays to the same single entry,
	 * and the checkpoint is always durable before the record that produced it can be forgotten.
	 */
	private void recordStateHistory(UpdateTransaction transaction) throws IOException {
		StateHistory.snapshotApplied(storage, transaction.plan(), snapshotKind(transaction), transaction.plan().modpackId(), transaction.transactionId);
	}

	private void snapshotBefore(UpdateTransaction transaction) throws IOException {
		StateHistory.snapshotIfDirty(storage, StateHistory.planPaths(transaction.plan()), ClientStateJournal.Kind.LIVE, transaction.plan().modpackId(), transaction.transactionId,
				transaction.plan());
	}

	private ClientStateJournal.Kind snapshotKind(UpdateTransaction transaction) throws IOException {
		ClientStateJournal.Kind declared = ClientStateJournal.Kind.parseDeclared(transaction.stateKind);
		if (declared != null) return declared;
		return switch (transaction.purpose) {
			case MODPACK_UPDATE -> {
				String modpackId = transaction.plan().modpackId();
				boolean seen = ClientStateJournal.open(storage).entries().stream()
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
		drift().beforeFirstMutation(transaction, publicationStarted);
		snapshotBefore(transaction);
		capturePreStates(transaction);
		capturePreservations(transaction);
		captureConflicts(transaction);
		if (!liveAlreadyApplied) applyOperations(transaction, current);
		current.set(null);
		if (!publicationStarted) {
			verifyManagedFinalState(transaction);
			drift().afterLiveOperations(transaction);
		}
		publishProjection(transaction);
		if (publicationStarted && (!managedStateMatches(transaction) || preserveNewerSelection || drift().configurationChanged(transaction)))
			throw new UpdateReplanRequiredException(null, "Mutable client state changed while publishing the update");
		drift().beforeFinalization(transaction);
		finalizeModpackState(transaction, preserveNewerSelection);
	}

	/** The non-projection half of the final state: every overlay and game-directory row the plan projected must be exactly what the plan says. */
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

	/** Builds and swaps the incoming projection unless the active tree already matches; no-ops when the projection was published earlier. */
	private void publishProjection(UpdateTransaction transaction) throws IOException {
		if (projection().verifyQuietly(storage.activeDirectory(), transaction.plan().projectedFinalState())) return;
		projection().build(transaction);
		setPhase(transaction, UpdateTransaction.Phase.SWAPPING);
		projection().swap(transaction);
	}

	/** The durable finalization: planned config, pack state, active-state pointer, and the launcher-metadata tail. */
	private void finalizeModpackState(UpdateTransaction transaction, boolean preserveNewerSelection) throws IOException {
		SelectedModpackTarget resolved = validator.resolvedTarget(transaction, validator.targetDocument(transaction));
		if (transaction.plan().plannedClientConfig() != null && !preserveNewerSelection)
			try {
				ReconfConfigs.save(storage.clientConfigFile(), transaction.plan().plannedClientConfig(), ClientConfigJsons.ClientConfigFieldsV3.class,
						ClientConfigJsons.ClientConfigFieldsV3::new);
			} catch (IOException e) {
				throw new ConfigTools.ConfigException("Failed to save client configuration", e);
			}
		if (!preserveNewerSelection) storage.writeSelectedModpackId(UpdateTransactionValidator.plannedFollow(transaction));
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			applyLauncherMetadata(transaction, resolved.flatTarget());
			storage.writeActiveState(transaction.plan().modpackId(), transaction.packTarget().contentToken(), resolved.document().ownershipLedger().toFields(), resolved.selection().intent());
		} else if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
			FileTrees.delete(storage.generatedCopiesGenerationDirectory(transaction.plan().modpackId(), transaction.plan().packTarget().contentToken()));
			storage.clearActiveState();
		} else {
			storage.clearActiveState();
		}
	}

	/**
	 * The launcher-metadata tail, previously a caller-supplied callback with one implementation: the planned switch
	 * axes ride the transaction's restart reasons, so no session state is needed to apply them.
	 */
	private static void applyLauncherMetadata(UpdateTransaction transaction, ModpackJsons.ModpackContentFields manifest) throws IOException {
		EnumSet<LauncherVersionSwapper.Axis> axes = switchAxes(transaction.plan().restartReasons());
		if (axes.isEmpty()) return;
		LauncherVersionSwapper.apply(axes, manifest.loader, manifest.loaderVersion, manifest.mcVersion);
	}

	private static EnumSet<LauncherVersionSwapper.Axis> switchAxes(Set<RestartReason> reasons) {
		EnumSet<LauncherVersionSwapper.Axis> axes = EnumSet.noneOf(LauncherVersionSwapper.Axis.class);
		if (reasons.contains(RestartReason.CHANGED_GAME_VERSION)) axes.add(LauncherVersionSwapper.Axis.GAME_VERSION);
		if (reasons.contains(RestartReason.CHANGED_LOADER_TYPE)) axes.add(LauncherVersionSwapper.Axis.LOADER_TYPE);
		if (reasons.contains(RestartReason.CHANGED_LOADER_VERSION)) axes.add(LauncherVersionSwapper.Axis.LOADER_VERSION);
		return axes;
	}

	/** Persists the terminal result fields of an interrupted transaction onto its journal record. */
	private void recordResult(UpdateTransaction transaction, UpdateTransaction.Status status, String operationName, Path blockedPath, String message, IOException cause)
			throws IOException {
		transaction.resultStatus = status;
		transaction.resultOperation = operationName;
		transaction.resultPath = blockedPath == null ? null : blockedPath.toString();
		transaction.resultMessage = message;
		try {
			ConfigTools.writeAtomicCompact(storage.transactionFile(), transaction);
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
		ConfigTools.writeAtomicCompact(storage.transactionFile(), transaction);
	}

	private void applyOperations(UpdateTransaction transaction, AtomicReference<Operation> current) throws IOException {
		for (Operation operation : transaction.plan().operations()) {
			if (operation.operation() != OperationType.INSTALL_OBJECT || operation.root() == Root.PROJECTION) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (FileIntegrity.matchesNamed(target, operation.expectedSize(), operation.expectedObjectHash(), fileCache)) continue;
			verifyExpectedExisting(operation, target);
			Path source = storage.objectFile(operation.expectedObjectHash());
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
			FileTrees.pruneEmptyAncestors(target, storage.root(operation.root(), transaction.plan().modpackId()));
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

	/** Verifies every planned capture against the live file and acquires its bytes into the object store; the entry's captures then pin them. */
	private void capturePreStates(UpdateTransaction transaction) throws IOException {
		if (transaction.plan().baselineCaptures().isEmpty()) return;
		for (BaselineCapture capture : transaction.plan().baselineCaptures()) {
			Path source = resolve(capture.root(), capture.relativePath(), transaction);
			if (capture.absent()) {
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new UpdateReplanRequiredException(source, "Captured path was expected to be absent: " + source);
				continue;
			}
			acquireImmutable(source, capture.expectedHash(), capture.expectedSize(), "Captured source changed");
		}
	}

	/** Acquires every planned preservation's bytes before mutation, so the state entry's change hashes name real objects. */
	private void capturePreservations(UpdateTransaction transaction) throws IOException {
		for (Preservation preservation : transaction.plan().preservations())
			acquireImmutable(resolve(preservation.root(), preservation.relativePath(), transaction), preservation.expectedHash(),
					preservation.expectedSize(), "Preserved source changed");
	}

	/** Acquires the local side of every preserve-local conflict before the pack's version overwrites it. */
	private void captureConflicts(UpdateTransaction transaction) throws IOException {
		for (Conflict conflict : transaction.plan().conflicts()) {
			if (conflict.action() != ConflictAction.PRESERVE_LOCAL) continue;
			acquireImmutable(storage.gamePath(conflict.sourcePath()), conflict.sourceHash(), conflict.sourceSize(), "Conflict source changed");
		}
	}

	/** The shared capture body: prove the live bytes are the planned ones, then pin them in the object store. */
	private void acquireImmutable(Path source, String hash, long size, String failureStory) throws IOException {
		if (!FileIntegrity.matches(source, size, hash, fileCache)) throw new UpdateReplanRequiredException(source, failureStory + ": " + source);
		VerifiedFileTransfer.copyAtomicImmutable(source, storage.objectFile(hash.toLowerCase(Locale.ROOT)), size, hash, fileCache);
	}

	private void cleanupTransactionDirectories(UpdateTransaction transaction) throws IOException {
		FileTrees.delete(storage.incomingDirectory());
		FileTrees.delete(storage.backupDirectory());
	}

	/**
	 * The stuck-update escape: drop in-flight publication, keep the last finalized generation, and retire the journal.
	 * {@code backup/} is the previous committed tree only while finalize has not yet written the active state.
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

	/**
	 * With no usable pending transaction the publication directories are provably unpinned - no journal owns their
	 * bytes - so leftovers of a crash whose journal was lost or set aside are swept here. Left alone they read as a
	 * publication already started, which would make the next update silently skip every live operation and its
	 * final-state verification while its commit reports success.
	 */
	public static void sweepUnpinnedPublicationDirectories(ClientStorage storage) throws IOException {
		ClientStorageMutation.run(storage, () -> {
			FileTrees.delete(storage.incomingDirectory());
			FileTrees.delete(storage.backupDirectory());
			return null;
		});
	}

	private boolean publicationStarted(UpdateTransaction transaction) {
		return ClientProjectionView.publicationStarted(storage, transaction);
	}

	private CommitDriftGates drift() {
		return new CommitDriftGates(storage, validator, fileCache);
	}

	private ProjectionPublication projection() {
		return new ProjectionPublication(storage, fileCache);
	}

	private Path resolve(Operation operation, UpdateTransaction transaction) throws IOException {
		return resolve(operation.root(), operation.relativePath(), transaction);
	}

	private Path resolve(Root root, String relativePath, UpdateTransaction transaction) throws IOException {
		return FileTrees.resolveConfined(storage.root(root, transaction.plan().modpackId()), UpdateTransactionValidator.normalizeOperationPath(relativePath), "Operation target");
	}

	private interface FileCacheWork<T> {
		T run(FileCache cache) throws IOException;
	}

	private <T> T withFileCache(FileCacheWork<T> work) throws IOException {
		if (fileCache != null) return work.run(fileCache);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory())) {
			fileCache = cache;
			try {
				return work.run(cache);
			} finally {
				fileCache = null;
			}
		}
	}

	static boolean isLockFailure(IOException exception, boolean windows) {
		Throwable current = exception;
		while (current != null) {
			// The JDK's Windows provider maps both ERROR_SHARING_VIOLATION (an open handle denies the delete -
			// the game itself, an antivirus scan) and ERROR_ACCESS_DENIED (ACLs, read-only) to
			// AccessDeniedException, with no lock-specific reason in the exception. Classifying every instance
			// as a lock is the conservative reading: the accepted cost is a permanent permission failure that
			// defers with a locked-file story, because the alternative misclassifies a genuine in-use file as a
			// terminal error. Other kernels name the sharing conflict in the message - only those count.
			if (windows && current instanceof AccessDeniedException) return true;
			if (current instanceof FileSystemException fileSystemException) {
				String detail = (Objects.toString(fileSystemException.getReason(), "") + " " + Objects.toString(fileSystemException.getMessage(), "")).toLowerCase(Locale.ROOT);
				if (detail.contains("used by another process") || detail.contains("being used by another process") || detail.contains("sharing violation")) return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static boolean isLockFailure(IOException exception) {
		return isLockFailure(exception, PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS);
	}
}
