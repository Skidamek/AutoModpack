package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ReconfConfigs;
import pl.skidam.automodpack_core.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.update.UpdateTransactionValidator.MutableInputDrift;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.PlatformUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.WindowsLockProbe;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The one durable client commit module: applies a reviewed plan or replays the persisted journal, deferring on locked
 * files. Every apply - fresh, recovered, rebuilt - goes through these doors, so the durability ordering (transaction
 * persisted and ownership receipt published before the first mutation), the replan budget, and the defer receipt are
 * facts of this module instead of choreography each caller re-derives. Dropping a stuck publication is not an apply and
 * lives in {@link PublicationRollback}.
 *
 * <p>
 * The replan budget: a rebuild at a drift trigger gets one apply, and that apply's own replan-required result is
 * terminal. Callers supply the rebuild as {@link Replan} - the rebuild is policy (the session gates it on the player's
 * review, boot gates it on outcome compatibility), the budget never is. A null {@link Replan} makes every drift
 * terminal, which is the helper process's spelling. A rebuild that refuses comes back as data through
 * {@link #rebuildOrRefuse} no matter which door asked for it, so one callback has exactly one error convention.
 *
 * <p>
 * The mutable-input gates repeat on purpose: each one guards a mutation that already happened behind the previous
 * (before the first mutation, after the live operations, after publication, before finalization, and once per recovery
 * trigger), so the count differs per path rather than being fixed. One evaluation is stat-backed and reads no file
 * content on a warm cache: the overlay gate is one directory walk plus one {@link FileCache} stat per overlay file, the
 * managed-state match one stat per projected non-projection row, and the configuration and selection gates one small
 * JSON document each.
 *
 * <p>
 * Each live operation is baseline, destination, or drift: still the planned before-state, already this apply's
 * after-state, or neither. Pin and apply share that classification. A third hash or size is the only replan. A
 * sharing violation while observing the live path is a lock, not drift: the helper retries it, and must not see
 * {@link UpdateTransaction.Status#REPLAN_REQUIRED}. {@link UpdatePlan#baselineCaptures()} names CAS retention and
 * history; it is not an apply-time gate.
 */
public final class UpdateCommit {
	private final ClientStorage storage;
	private final UpdateTransactionValidator validator;
	private FileCache fileCache;
	private ProjectionPublication projection;

	/** A transaction to apply now. {@code unpublishedTarget} names the target document when it is not yet in the generation store; null otherwise. */
	public record Built(UpdateTransaction transaction, SelectedModpackTarget unpublishedTarget) {
		public Built {
			Objects.requireNonNull(transaction, "transaction");
			if (unpublishedTarget != null && transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE)
				throw new IllegalArgumentException("Only a modpack update transaction carries an unpublished target");
		}

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
	public record Cause(UpdateTransaction transaction, Path blockedPath, String message) {}

	/** Rebuilds the plan from mutable inputs; the caller owns the review or compatibility gate the rebuild must pass. */
	@FunctionalInterface
	public interface Replan {
		Built rebuild(Cause cause) throws IOException;
	}

	/** One apply request: a fresh transaction, or a replay of the persisted journal. */
	public record Fresh(TransactionSource source, Replan rebuild) {}
	public record Recover(Replan rebuild) {}

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

		@Override
		public String held() {
			return null;
		}
	}

	/** The transaction an attempt ended with and the facts of why it did not apply, shared by both blocked outcomes. */
	public sealed interface Blocked permits Deferred, ReplanRequired {
		UpdateTransaction transaction();

		String operation();

		Path blockedPath();

		String message();

		/** The lock probe's holder receipt, or null when this outcome captured none. */
		String held();
	}

	public sealed interface Outcome permits Applied, Deferred, ReplanRequired, Idle {
		/** The journal status spelling of this outcome, for the receipts logs and policy code already speak. */
		UpdateTransaction.Status status();

		/** Whether the attempt is over: nothing is left to apply and nothing left to rebuild. */
		default boolean settled() {
			return this instanceof Applied || this instanceof Idle;
		}
	}

	public UpdateCommit(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage, "storage");
		this.validator = new UpdateTransactionValidator(storage);
	}

	/**
	 * The reviewed-commit door: applies the plan, rebuilds once on drift, and returns the plan that durably applied.
	 * Throws {@link UpdateExecutionException} after journaling a FAILED receipt, {@link UpdateReplanRequiredException} when
	 * the apply exhausts its rebuild, {@link UpdateDeferredException} when a locked file owns the apply, and
	 * {@link IOException} for physical trouble.
	 */
	public UpdatePlan commit(TransactionSource source, Replan rebuild) throws IOException {
		Outcome outcome = run(new Fresh(source, rebuild));
		if (outcome instanceof Applied applied) return applied.transaction().plan();
		if (outcome instanceof ReplanRequired replan) throw new UpdateReplanRequiredException(replan.blockedPath(), replan.message());
		if (outcome instanceof Deferred deferred) throw new UpdateDeferredException(deferred.transaction().transactionId, deferred.blockedPath(), deferred.message());
		throw new IllegalStateException("A fresh commit cannot be idle");
	}

	/** Applies a fresh transaction, replanning once at the first drift; a replan-required result past that rebuild is data, and {@link #commit} is the door that turns it into an exception. */
	public Outcome run(Fresh fresh) throws IOException {
		Outcome outcome = apply(fresh.source().build());
		if (!(outcome instanceof ReplanRequired replan) || fresh.rebuild() == null) return outcome;
		LOGGER.warn("The update apply needs a replan: {}; rebuilding the plan from mutable inputs", replan.message());
		// The first attempt's reason is the receipt for why a replan is happening at all; never let the retry swallow it.
		return rebuildOrRefuse(fresh.rebuild(), new Cause(replan.transaction(), replan.blockedPath(), replan.message())).outcome();
	}

	/**
	 * Replays the persisted journal with up to two replans: one where a drift trigger speaks before any mutation, and one
	 * where the replay found the pending apply had already landed but its inputs moved meanwhile. The journal is read once
	 * and replayed under the mutation lock, which every replay re-validates against the live tree and re-checks for drift,
	 * so the read never needs to be a second one. Every blocked or replan-required outcome is returned as data.
	 */
	public Outcome run(Recover recover) throws IOException {
		UpdateTransaction pending = UpdateTransaction.read(storage.transactionFile());
		if (pending == null) return new Idle();
		return withFileCache(cache -> recoverPending(pending, recover.rebuild()));
	}

	/**
	 * One recovery pass: the advisory drift trigger runs unlocked, like boot's pre-checks always did, so a rebuild is
	 * never spent on an input that already moved; the authoritative re-check lives inside {@link #executeRecovered} under
	 * the mutation lock. A rebuild's own refusal and a replan-required result past the last allowed replan are terminal
	 * here, which is why the budget is two doors rather than a counter.
	 */
	private Outcome recoverPending(UpdateTransaction pending, Replan rebuild) throws IOException {
		boolean publicationStarted = publicationStarted(pending);
		// With no rebuild the advisory trigger has no second door to open, so the authoritative check under the lock is the only one that can answer.
		if (!publicationStarted && (rebuild == null || !replanRequired(pending, publicationStarted))) return executeRecovered(pending, publicationStarted);
		if (!publicationStarted) {
			LOGGER.warn("The pending update's inputs moved after planning; rebuilding the plan from mutable inputs");
			Rebuilt first = rebuildOrRefuse(rebuild, new Cause(pending, null, "Pending update input changed after planning"));
			if (first.refused() || !(first.outcome() instanceof ReplanRequired replan)) return first.outcome();
			LOGGER.warn("The rebuilt recovery needs a replan of its own: {}; rebuilding the plan from mutable inputs", replan.message());
			return rebuildOrRefuse(rebuild, new Cause(replan.transaction(), replan.blockedPath(), replan.message())).outcome();
		}
		Outcome outcome = executeRecovered(pending, publicationStarted);
		if (!(outcome instanceof Applied) || rebuild == null || !replanRequired(pending, publicationStarted)) return outcome;
		LOGGER.warn("The pending update changed after it applied; rebuilding the plan from mutable inputs");
		outcome = rebuildOrRefuse(rebuild, new Cause(pending, null, "Pending update changed after the recovery applied it")).outcome();
		return outcome instanceof ReplanRequired ? new ReplanRequired(pending, null, null, "Pending update still requires a fresh plan") : outcome;
	}

	/** One rebuild's answer, and whether the caller refused to produce a plan at all - a refusal no second rebuild can improve on. */
	private record Rebuilt(Outcome outcome, boolean refused) {}

	/** The one error convention of the rebuild callback: a refusal is terminal and comes back as data, whoever asked for the rebuild. */
	private Rebuilt rebuildOrRefuse(Replan rebuild, Cause cause) throws IOException {
		try {
			return new Rebuilt(apply(rebuild.rebuild(cause)), false);
		} catch (UpdateReplanRequiredException refused) {
			// The reviewed or compatible outcome no longer holds, and only the next fresh review can re-approve it.
			return new Rebuilt(new ReplanRequired(cause.transaction(), null, refused.changedPath(), refused.getMessage()), true);
		}
	}

	/** The fresh-apply path: validate, retire or verify whatever was pending, persist this journal, then execute it. */
	private Outcome apply(Built built) throws IOException {
		UpdateTransaction transaction = built.transaction();
		return ClientStorageMutation.run(storage, () -> withFileCache(cache -> {
			validator.validate(transaction, built.unpublishedTarget(), true, cache);
			verifySelectionUnchanged(transaction);
			preparePendingReplacement();
			ConfigTools.writeAtomicCompact(storage.transactionFile(), transaction);
			// Receipt before the first mutation: other processes sharing the store see this instance only through
			// its ownership receipt, so the journaled plan's objects must be pinned by name before apply runs.
			ClientObjectStore.publishOwnership(storage);
			return executeTransaction(transaction);
		}));
	}

	/** The replay path: the caller already read the durable journal, so validation and execution re-check it and apply it directly. */
	private Outcome executeRecovered(UpdateTransaction current, boolean publicationStarted) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			if (!publicationStarted && replanRequired(current, publicationStarted)) return new ReplanRequired(current, null, null, "Pending update input changed after planning");
			validator.validateUnchecked(current, null, !publicationStarted, fileCache);
			if (!publicationStarted) verifySelectionUnchanged(current);
			return executeTransaction(current);
		});
	}

	private void preparePendingReplacement() throws IOException {
		if (Files.exists(storage.repairJournalFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("An offline repair must finish before an update can start");
		UpdateTransaction pending = UpdateTransaction.read(storage.transactionFile());
		if (pending == null) {
			PublicationRollback.sweepUnpinnedDirectories(storage);
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
				&& !projection.verifyQuietly(storage.activeDirectory(), pending.plan().projectedFinalState()))
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
		boolean preserveNewerSelection = publicationStarted && validator.mutableInputDrift(transaction, fileCache).selection();
		try {
			transaction.resultStatus = null;
			transaction.resultOperation = null;
			transaction.resultPath = null;
			transaction.resultMessage = null;
			setPhase(transaction, UpdateTransaction.Phase.PREPARING);
			applyModpackTransaction(transaction, current, publicationStarted, publicationStarted || managedStateMatches(transaction), preserveNewerSelection);
			persistPhase(transaction, UpdateTransaction.Phase.COMMITTED);
		} catch (IOException e) {
			Operation currentOperation = current.get();
			if (blockedPath == null && currentOperation != null) blockedPath = resolve(currentOperation, transaction);
			if (e instanceof UpdateReplanRequiredException replan && blockedPath == null) blockedPath = replan.changedPath();
			if (blockedPath == null && e instanceof FileSystemException fileSystem && fileSystem.getFile() != null && !fileSystem.getFile().isBlank()) blockedPath = Path.of(fileSystem.getFile());
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
		// The tail retires the journal, so it sits outside the catch: a failure here would journal a FAILED receipt for a
		// transaction whose journal is already gone, and the next boot would replay a retired apply.
		return finalizeCommitted(transaction);
	}

	/**
	 * The one durable commit tail, shared by the live path and the recovery of a crash after the COMMITTED phase
	 * persisted: drop the working directories, checkpoint the state history, retire the journal, and refresh the
	 * ownership receipt. The receipt published at commit start names every object the journal pinned, and retiring it
	 * is the only reference loss since, so the stale on-disk receipt protected everything until this refresh replaces
	 * it with exactly the durable set. A publish is a full-state sweep (~2ms over a 200-entry journal, pinned by
	 * {@code ClientObjectStoreTest.referenceSweepStaysCheapOnATwoHundredEntryJournal}), so a commit publishes at its
	 * start and here, and never per file. The publish is bookkeeping and stays out of the failure path: it runs under
	 * the lock, every collection republishes it before deleting anything, so a failed one only makes the next collection
	 * refuse, while failing a committed apply here would report a failure over bytes that already landed.
	 */
	private Outcome finalizeCommitted(UpdateTransaction transaction) throws IOException {
		cleanupTransactionDirectories(transaction);
		recordStateHistory(transaction);
		Files.deleteIfExists(storage.transactionFile());
		try {
			ClientObjectStore.publishOwnership(storage);
		} catch (IOException e) {
			LOGGER.error("Transaction {} committed, but the client object ownership receipt could not be published; content collection stays refused until one publishes", transaction.transactionId, e);
		}
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

	/** The modpack apply sequence: pin remaining before-bytes, live operations, projection publication, and durable finalization. */
	private void applyModpackTransaction(UpdateTransaction transaction, AtomicReference<Operation> current, boolean publicationStarted, boolean liveAlreadyApplied,
			boolean preserveNewerSelection) throws IOException {
		beforeFirstMutation(transaction, publicationStarted);
		capturePinnedBytes(transaction);
		snapshotBefore(transaction);
		if (!liveAlreadyApplied) applyOperations(transaction, current);
		current.set(null);
		if (!publicationStarted) {
			verifyManagedFinalState(transaction);
			afterLiveOperations(transaction);
		}
		publishProjection(transaction);
		if (publicationStarted && (!managedStateMatches(transaction) || preserveNewerSelection || configurationChanged(transaction)))
			throw new UpdateReplanRequiredException(null, "Mutable client state changed while publishing the update");
		beforeFinalization(transaction);
		finalizeModpackState(transaction, preserveNewerSelection);
	}

	/** One planned byte the apply must prove and pin before a single live file moves. */
	private record Pinned(Path path, String hash, long size, String failureStory) {}

	/** Still the planned before-state, already this apply's after-state, or neither. */
	private enum InFlight {
		BASELINE, DESTINATION, DRIFT
	}

	/** Pin remaining before-bytes of every live operation so a later mutation of this attempt still has them in CAS. */
	private void capturePinnedBytes(UpdateTransaction transaction) throws IOException {
		for (Operation operation : transaction.plan().operations()) {
			if (operation.root() == Root.PROJECTION) continue;
			Path live = resolve(operation, transaction);
			if (requireInFlight(live, operation) == InFlight.DESTINATION) continue;
			if (operation.expectedExistingHash() == null) continue;
			acquireImmutable(new Pinned(live, operation.expectedExistingHash(), Files.size(live), driftStory(operation, live)));
		}
	}

	private InFlight inFlight(Path live, Operation operation) throws IOException {
		FileCache.StatSnapshot snapshot = snapshotIfPresent(live);
		boolean atDestination = operation.operation() == OperationType.DELETE
				? snapshot == null
				: snapshot != null && FileIntegrity.observeNamed(live, operation.expectedSize(), operation.expectedObjectHash(), fileCache);
		boolean atBaseline = atBaseline(operation, snapshot, live);
		if (atDestination && !atBaseline) return InFlight.DESTINATION;
		if (atBaseline) return InFlight.BASELINE;
		return InFlight.DRIFT;
	}

	private static FileCache.StatSnapshot snapshotIfPresent(Path live) throws IOException {
		try {
			return FileCache.statSnapshot(live);
		} catch (NoSuchFileException e) {
			return null;
		}
	}

	private boolean atBaseline(Operation operation, FileCache.StatSnapshot snapshot, Path live) throws IOException {
		if (operation.expectedExistingHash() == null) return snapshot == null;
		return snapshot != null && snapshot.isTrackedRegularFile() && FileIntegrity.observe(live, snapshot.size(), operation.expectedExistingHash(), fileCache);
	}

	private InFlight requireInFlight(Path live, Operation operation) throws IOException {
		InFlight state = inFlight(live, operation);
		if (state == InFlight.DRIFT) throw new UpdateReplanRequiredException(live, driftStory(operation, live));
		return state;
	}

	private static String driftStory(Operation operation, Path live) {
		String described = operation.root() == Root.OVERLAY ? "Client overlay" : "Game-directory";
		return described + (operation.expectedExistingHash() == null ? " target appeared after planning: " : " target changed after planning: ") + live;
	}

	private boolean matchesPlannedDestination(Path live, ProjectedFile destination) throws IOException {
		if (destination == null) return false;
		if (!destination.present()) return snapshotIfPresent(live) == null;
		return FileIntegrity.observeNamed(live, destination.expectedSize(), destination.expectedHash(), fileCache);
	}

	/** The shared capture body: prove the live bytes are the planned ones, then pin them in the object store. */
	private void acquireImmutable(Pinned pinned) throws IOException {
		if (!FileIntegrity.observe(pinned.path(), pinned.size(), pinned.hash(), fileCache)) throw new UpdateReplanRequiredException(pinned.path(), pinned.failureStory() + ": " + pinned.path());
		VerifiedFileTransfer.copyAtomicImmutable(pinned.path(), storage.objectFile(pinned.hash().toLowerCase(Locale.ROOT)), pinned.size(), pinned.hash(), fileCache);
	}

	/** The non-projection half of the final state: every overlay and game-directory row the plan projected must be exactly what the plan says. */
	private void verifyManagedFinalState(UpdateTransaction transaction) throws IOException {
		for (ProjectedFile projected : transaction.plan().projectedFinalState()) {
			if (projected.root() == Root.PROJECTION) continue;
			Path target = resolve(projected.root(), projected.relativePath(), transaction);
			if (!matchesPlannedDestination(target, projected))
				throw new UpdateReplanRequiredException(target, (projected.present() ? "Projected target changed during update: " : "Projected absent target appeared during update: ") + target);
		}
	}

	private boolean managedStateMatches(UpdateTransaction transaction) throws IOException {
		try {
			verifyManagedFinalState(transaction);
			return true;
		} catch (UpdateReplanRequiredException e) {
			return false;
		}
	}

	/**
	 * Builds and swaps the incoming projection unless the active tree already matches. {@code incoming/} is this
	 * publication's own build area, so a resumed publication that already holds exactly this plan is swapped as it
	 * stands: the alternative deletes and re-links every projected file and walks the tree again, while proving that
	 * tree is two stats per file and no content read. A half-built area fails the same proof and is rebuilt.
	 */
	private void publishProjection(UpdateTransaction transaction) throws IOException {
		if (projection.verifyQuietly(storage.activeDirectory(), transaction.plan().projectedFinalState())) return;
		if (!projection.verifyQuietly(storage.incomingDirectory(), transaction.plan().projectedFinalState())) projection.build(transaction);
		setPhase(transaction, UpdateTransaction.Phase.SWAPPING);
		projection.swap(transaction);
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
			storage.writeActiveState(resolved.document(), resolved.selection().intent());
		} else if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
			GeneratedCopyState.removeGeneration(storage, transaction.plan().modpackId(), transaction.plan().packTarget().contentToken());
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
			if (requireInFlight(target, operation) == InFlight.DESTINATION) continue;
			Path source = storage.objectFile(operation.expectedObjectHash());
			VerifiedFileTransfer.copyAtomic(source, target, operation.expectedSize(), operation.expectedObjectHash(), fileCache);
		}
		for (Operation operation : transaction.plan().operations()) {
			if (operation.operation() != OperationType.DELETE || operation.root() == Root.PROJECTION) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (requireInFlight(target, operation) != InFlight.DESTINATION) Files.delete(target);
			FileTrees.pruneEmptyAncestors(target, storage.root(operation.root(), transaction.plan().modpackId()));
		}
	}

	private void cleanupTransactionDirectories(UpdateTransaction transaction) throws IOException {
		FileTrees.delete(storage.incomingDirectory());
		FileTrees.delete(storage.backupDirectory());
	}

	private boolean publicationStarted(UpdateTransaction transaction) {
		return ClientProjectionView.publicationStarted(storage, transaction);
	}

	/** The recovery triggers' question: has any mutable input moved such that the plan must be built again before mutation continues. */
	private boolean replanRequired(UpdateTransaction transaction, boolean publicationStarted) throws IOException {
		MutableInputDrift drift = validator.mutableInputDrift(transaction, fileCache);
		if (publicationStarted) return drift.configuration() || transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE && (!overlayStateMatches(transaction) || drift.selection());
		return drift.any();
	}

	/** The pre-mutation configuration gate; a publication that already started cannot undo its config side effects, so it is exempt. */
	private void beforeFirstMutation(UpdateTransaction transaction, boolean publicationStarted) throws IOException {
		if (!publicationStarted && validator.mutableInputDrift(transaction, fileCache).configuration())
			throw new UpdateReplanRequiredException(null, "Client configuration changed after planning the update");
	}

	/** The selection gate a fresh commit and a not-yet-published recovery both run before anything moves. */
	private void verifySelectionUnchanged(UpdateTransaction transaction) throws IOException {
		if (validator.mutableInputDrift(transaction, fileCache).selection()) throw new IOException("Group selection changed after planning for modpack " + transaction.plan().modpackId());
	}

	/** The gate after the live operations: both the selection and the configuration the plan assumed must still hold. */
	private void afterLiveOperations(UpdateTransaction transaction) throws IOException {
		MutableInputDrift applied = validator.mutableInputDrift(transaction, fileCache);
		if (applied.selection()) throw new UpdateReplanRequiredException(null, "Group selection changed while applying the update");
		if (applied.configuration()) throw new UpdateReplanRequiredException(null, "Client configuration changed while applying the update");
	}

	private boolean configurationChanged(UpdateTransaction transaction) throws IOException {
		return validator.mutableInputDrift(transaction, fileCache).configuration();
	}

	/** The last gate before durable finalization: no mutable input may have moved during publication either. */
	private void beforeFinalization(UpdateTransaction transaction) throws IOException {
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
			projection = new ProjectionPublication(storage, cache);
			try {
				return work.run(cache);
			} finally {
				fileCache = null;
				projection = null;
			}
		}
	}

	/**
	 * On Windows a denied delete is how an open handle reports a sharing violation; on other kernels it is a plain
	 * permission problem - a permanent failure, not a lock the update should defer behind. Classifying every Windows
	 * instance as a lock is the conservative reading: the accepted cost is a permanent permission failure that defers
	 * with a locked-file story, because the alternative misclassifies a genuine in-use file as a terminal error. Other
	 * kernels name the sharing conflict in the message - only those count.
	 */
	static boolean isLockFailure(IOException exception, boolean windows) {
		Throwable current = exception;
		while (current != null) {
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
