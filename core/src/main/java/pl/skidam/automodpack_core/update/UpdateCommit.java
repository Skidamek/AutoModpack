package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ReconfConfigs;
import pl.skidam.automodpack_core.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.PlatformUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.WindowsLockProbe;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * The one durable client commit module: applies a reviewed plan or replays the persisted journal. Every apply - fresh,
 * recovered, rebuilt - goes through these doors, so the durability ordering (transaction persisted and ownership receipt
 * published before the first mutation), the replan budget, and the defer receipt are facts of this module instead of
 * choreography each caller re-derives.
 *
 * <p>
 * Every apply is best effort and rolls forward. Before the first live write a drifted input replans: the plan no longer
 * describes what the player approved. From the first write on nothing replans or rolls back. Each live operation that
 * has not landed is written over whatever its path holds - the state history snapshot taken at the start of every run
 * keeps those bytes - and a path some process holds open stays owed while the rest of the transaction runs. The journal
 * then keeps exactly the operations still owed, and the projection is published only once none are left, so a started
 * publication proves every live operation landed, the invariant journals of earlier versions share. A file that changes
 * after its operation landed is therefore never this module's business again: reconciliation keeps an editable file as
 * the player's edit and resets a pack-owned one. An action started while another is still owed plans on top of it, and
 * replacing the owed journal needs no gate: the replacement rebuilds the working directories and inherits its deletes.
 *
 * <p>
 * The replan budget: a rebuild at a drift trigger gets one apply, and that apply's own replan-required result is
 * terminal. Callers supply the rebuild as {@link Replan} - the rebuild is policy (the session gates it on the player's
 * review, boot gates it on outcome compatibility), the budget never is. A null {@link Replan} makes every drift
 * terminal, which is the helper process's spelling. A rebuild that refuses comes back as data through
 * {@link #rebuildOrRefuse} no matter which door asked for it, so one callback has exactly one error convention.
 *
 * <p>
 * Each live operation is baseline, destination, or drift: still the planned before-state, already this apply's
 * after-state, or neither. A sharing violation while observing or writing the live path is a lock, not drift: the
 * operation stays owed and the helper or the next boot retries it. {@link UpdatePlan#baselineCaptures()} names CAS
 * retention and history; it is not an apply-time gate.
 */
public final class UpdateCommit {
	private final ClientStorage storage;
	private final UpdateTransactionValidator validator;
	private final Probe probe;
	private FileCache fileCache;
	/** The loader-scanned paths this committer changed, in order: a boot that does not relaunch after them names them. */
	private final List<String> scannedChanges = new ArrayList<>();
	/** The scanned change of a projection a directory-scanning loader took before preload. */
	static final String PROJECTION_SCANNED_CHANGE = "changed the modpack projection";

	/** The loader-scanned paths every run of this committer changed so far, a failed one's included. */
	public List<String> scannedChanges() {
		return List.copyOf(scannedChanges);
	}

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

	/** The transaction landed whole. {@code scannedChanges}: the {@code mods/} files and launcher metadata this committer changed, which a running loader scanned before it. */
	public record Applied(UpdateTransaction transaction, List<String> scannedChanges) implements Outcome {
		public UpdateTransaction.Status status() {
			return UpdateTransaction.Status.SUCCESS;
		}

		public boolean scannedFilesChanged() {
			return !scannedChanges.isEmpty();
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

	/** The durable steps of one apply, in order; the test probe crashes between them. */
	enum Step {
		JOURNALED, LIVE_APPLIED, PROJECTION_MARKED, PROJECTION_CONVERGED, FINALIZED, COMMITTED
	}

	/** Test seam: a crash between durable steps, and a held file in front of every live write. */
	interface Probe {
		Probe NONE = new Probe() {
		};

		default void reached(Step step) throws IOException {}

		default void beforeWrite(Path target) throws IOException {}
	}

	/** A path some process held open, and the operation that owes it; a null operation is a path of the projection convergence. */
	private record Lock(Operation operation, Path path, IOException cause) {}

	public UpdateCommit(ClientStorage storage) {
		this(storage, Probe.NONE);
	}

	UpdateCommit(ClientStorage storage, Probe probe) {
		this.storage = Objects.requireNonNull(storage, "storage");
		this.validator = new UpdateTransactionValidator(storage);
		this.probe = Objects.requireNonNull(probe, "probe");
	}

	/**
	 * The reviewed-commit door: applies the plan, rebuilds once on drift, and returns the plan that durably applied.
	 * Throws {@link UpdateExecutionException} after journaling a FAILED receipt, {@link UpdateReplanRequiredException} when
	 * the apply exhausts its rebuild, {@link UpdateDeferredException} when a held file keeps part of the apply owed, and
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
	 * Replays the persisted journal. Before its first live write a drifted journal may be rebuilt twice - once for the
	 * drift the replay found, once for a drift the rebuilt apply found - and a started one is only ever rolled forward.
	 * The journal is read once and replayed under the mutation lock, which every replay re-validates against the live
	 * tree, so the read never needs to be a second one. Every blocked or replan-required outcome is returned as data.
	 */
	public Outcome run(Recover recover) throws IOException {
		UpdateTransaction pending = UpdateTransaction.read(storage.transactionFile());
		if (pending == null) return new Idle();
		return withFileCache(cache -> recoverPending(pending, recover.rebuild()));
	}

	/** One recovery pass: the advisory drift trigger runs unlocked, so a rebuild is never spent on an input that already moved; the authoritative re-check lives in {@link #executeRecovered}. */
	private Outcome recoverPending(UpdateTransaction pending, Replan rebuild) throws IOException {
		boolean started = mutationStarted(pending);
		if (started || rebuild == null || !replanRequired(pending)) return executeRecovered(pending, started);
		LOGGER.warn("The pending update's inputs moved after planning; rebuilding the plan from mutable inputs");
		Rebuilt first = rebuildOrRefuse(rebuild, new Cause(pending, null, "Pending update input changed after planning"));
		if (first.refused() || !(first.outcome() instanceof ReplanRequired replan)) return first.outcome();
		LOGGER.warn("The rebuilt recovery needs a replan of its own: {}; rebuilding the plan from mutable inputs", replan.message());
		return rebuildOrRefuse(rebuild, new Cause(replan.transaction(), replan.blockedPath(), replan.message())).outcome();
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

	/** The fresh-apply path: validate, settle whatever was pending, persist this journal, then execute it. */
	private Outcome apply(Built built) throws IOException {
		UpdateTransaction transaction = built.transaction();
		return ClientStorageMutation.run(storage, () -> withFileCache(cache -> {
			validator.validate(transaction, built.unpublishedTarget(), true, cache);
			verifySelectionUnchanged(transaction);
			replacePending();
			ConfigTools.writeAtomicCompact(storage.transactionFile(), transaction);
			// Receipt before the first mutation: other processes sharing the store see this instance only through
			// its ownership receipt, so the journaled plan's objects must be pinned by name before apply runs.
			ClientObjectStore.publishOwnership(storage);
			probe.reached(Step.JOURNALED);
			return executeTransaction(transaction);
		}));
	}

	/** The replay path: the caller already read the durable journal, so validation and execution re-check it and apply it directly. */
	private Outcome executeRecovered(UpdateTransaction current, boolean started) throws IOException {
		return ClientStorageMutation.run(storage, () -> {
			if (!started && replanRequired(current)) return new ReplanRequired(current, null, null, "Pending update input changed after planning");
			validator.validateUnchecked(current, null, !started, fileCache);
			if (!started) verifySelectionUnchanged(current);
			return executeTransaction(current);
		});
	}

	/**
	 * A fresh action replaces whatever is pending. A committed journal only lacks its tail, which runs here; any other one
	 * was planned on top of, so its working directories are settled and the new journal takes over what it still owed.
	 */
	private void replacePending() throws IOException {
		if (Files.exists(storage.repairJournalFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("An offline repair must finish before an update can start");
		UpdateTransaction pending = UpdateTransaction.read(storage.transactionFile());
		if (pending != null && pending.phase == UpdateTransaction.Phase.COMMITTED) {
			// A crash can land after the COMMITTED marker but before its state-history checkpoint; replaying the tail here
			// records the entry (idempotent by transaction id) before the record that produced it retires.
			recordStateHistory(pending);
			Files.deleteIfExists(storage.transactionFile());
		}
		PublicationSweep.sweepUnpinnedDirectories(storage);
	}

	private Outcome executeTransaction(UpdateTransaction transaction) throws IOException {
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE)
			// The copies' ownership record is rewritten before every apply run, recovery replays included, so a crash
			// mid-apply never leaves installed copies unowned; a doc of a run that never applied is inert until it does.
			GeneratedCopyState.fromCopies(transaction.plan().modpackId(), transaction.plan().packTarget().contentToken(), transaction.selectionDigest(), transaction.plan().generatedCopies()).write(storage);
		if (transaction.phase == UpdateTransaction.Phase.COMMITTED) return finalizeCommitted(transaction);
		AtomicReference<Operation> current = new AtomicReference<>();
		boolean started = mutationStarted(transaction);
		boolean preserveNewerSelection = started && validator.mutableInputDrift(transaction, fileCache).selection();
		UpdatePlan reviewed = transaction.plan();
		List<Lock> locks;
		try {
			transaction.resultStatus = null;
			transaction.resultOperation = null;
			transaction.resultPath = null;
			transaction.resultMessage = null;
			locks = applyModpackTransaction(transaction, current, started, preserveNewerSelection);
			if (locks.isEmpty()) {
				// Only an owed journal is trimmed; the one that lands whole keeps the plan this run was handed.
				transaction.plan = reviewed;
				persistPhase(transaction, UpdateTransaction.Phase.COMMITTED);
				probe.reached(Step.COMMITTED);
			}
		} catch (IOException e) {
			Operation currentOperation = current.get();
			Path blockedPath = currentOperation == null ? null : resolve(currentOperation, transaction);
			if (e instanceof UpdateReplanRequiredException replan && blockedPath == null) blockedPath = replan.changedPath();
			if (blockedPath == null && e instanceof FileSystemException fileSystem && fileSystem.getFile() != null && !fileSystem.getFile().isBlank()) blockedPath = Path.of(fileSystem.getFile());
			String operationName = currentOperation == null ? null : currentOperation.operation().name();
			if (e instanceof UpdateReplanRequiredException) {
				transaction.phase = UpdateTransaction.Phase.DEFERRED;
				recordResult(transaction, UpdateTransaction.Status.REPLAN_REQUIRED, operationName, blockedPath, e.getMessage(), e);
				return new ReplanRequired(transaction, operationName, blockedPath, e.getMessage());
			}
			if (isLockFailure(e)) return defer(transaction, List.of(new Lock(currentOperation, blockedPath != null ? blockedPath : storage.activeDirectory(), e)));
			String condition = StorageConditions.condition(e);
			if (condition != null) {
				// No wait fixes it, so it is a failure with the reason the player can act on, never a deferral that restarts.
				e = new IOException(StorageConditions.describe(condition, e), e);
				LOGGER.error(e.getMessage());
			}
			recordResult(transaction, UpdateTransaction.Status.FAILED, operationName, blockedPath, e.getMessage(), e);
			throw new UpdateExecutionException(operationName, blockedPath, e);
		}
		if (!locks.isEmpty()) return defer(transaction, locks);
		// The tail retires the journal, so it sits outside the catch: a failure here would journal a FAILED receipt for a
		// transaction whose journal is already gone, and the next boot would replay a retired apply.
		return finalizeCommitted(transaction);
	}

	/** The defer receipt: the journal keeps what is still owed and names the first held path, so every later boot re-announces who held the update. */
	private Deferred defer(UpdateTransaction transaction, List<Lock> locks) throws IOException {
		Lock first = locks.get(0);
		// A journal that already wrote keeps its started phase, the one fact that tells every later reader to roll it forward.
		if (transaction.phase == UpdateTransaction.Phase.PLANNED) transaction.phase = UpdateTransaction.Phase.DEFERRED;
		String held = WindowsLockProbe.describeHeld(first.path());
		if (held != null) LOGGER.warn("The update is blocked by paths held open by other processes: {}", held);
		String message = (locks.size() == 1 ? "" : locks.size() + " paths are held open, first ") + first.cause().getMessage() + (held == null ? "" : " (held: " + held + ")");
		String operationName = first.operation() == null ? null : first.operation().operation().name();
		recordResult(transaction, UpdateTransaction.Status.DEFERRED_LOCKED, operationName, first.path(), message, first.cause());
		return new Deferred(transaction, operationName, first.path(), message, held);
	}

	/**
	 * The one durable commit tail, shared by the live path and the recovery of a crash after the COMMITTED phase
	 * persisted: drop the working directories, checkpoint the state history, retire the journal, and refresh the
	 * ownership receipt. The receipt published at commit start names every object the journal pinned, and retiring it
	 * is the only reference loss since, so the stale on-disk receipt protected everything until this refresh replaces
	 * it with exactly the durable set. A publish is a full-state sweep whose cost tracks durable history, not the
	 * files this update changed (pinned by {@code ClientObjectStoreTest.referenceSweepStaysCheapOnATwoHundredEntryJournal}),
	 * so a commit publishes at its start and here, and never per file. The publish is bookkeeping and stays out of the
	 * failure path: it runs under
	 * the lock, every collection republishes it before deleting anything, so a failed one only makes the next collection
	 * refuse, while failing a committed apply here would report a failure over bytes that already landed.
	 */
	private Outcome finalizeCommitted(UpdateTransaction transaction) throws IOException {
		// P2: the converged tree is loadable from the moment the transaction it belongs to committed; a replayed tail settles it again.
		if (ProjectionState.owed(storage)) ProjectionState.settled(storage, transaction.transactionId);
		PublicationSweep.settleLegacyDirectories(storage);
		recordStateHistory(transaction);
		Files.deleteIfExists(storage.transactionFile());
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
			try {
				new ClientGenerationStore(storage).forgetModpack(transaction.plan().modpackId());
			} catch (IOException e) {
				LOGGER.warn("Modpack removal committed, but retained client state cleanup was deferred and can be retried", e);
			}
		}
		try {
			ClientObjectStore.publishOwnership(storage);
		} catch (IOException e) {
			LOGGER.error("Transaction {} committed, but the client object ownership receipt could not be published; content collection stays refused until one publishes", transaction.transactionId, e);
		}
		return new Applied(transaction, scannedChanges());
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

	/**
	 * The modpack apply sequence: the pre-mutation gates and pins, the live operations still owed, the tombstone step,
	 * the durable PROJECTED marker, the projection convergence, and durable finalization. PROJECTED is persisted only
	 * once every live operation landed and before the first projection write, so a replay of a published journal never
	 * writes a live file again (#580: a landed editable file is the player's). Returns the held paths that keep the
	 * transaction owed.
	 */
	private List<Lock> applyModpackTransaction(UpdateTransaction transaction, AtomicReference<Operation> current, boolean started, boolean preserveNewerSelection) throws IOException {
		if (!started) {
			if (validator.mutableInputDrift(transaction, fileCache).configuration()) throw new UpdateReplanRequiredException(null, "Client configuration changed after planning the update");
			requireLauncherWritable(transaction);
			capturePinnedBytes(transaction);
		}
		boolean published = publicationStarted(transaction);
		snapshotBefore(transaction);
		if (!started) persistPhase(transaction, UpdateTransaction.Phase.PREPARING);
		else if (!published) setPhase(transaction, UpdateTransaction.Phase.PREPARING);
		if (!published) {
			List<Lock> owed = applyOperations(transaction, current);
			current.set(null);
			applyOverlayTombstones(transaction);
			probe.reached(Step.LIVE_APPLIED);
			if (!owed.isEmpty()) return owed;
		} else {
			applyOverlayTombstones(transaction);
		}
		if (transaction.phase != UpdateTransaction.Phase.PROJECTED) persistPhase(transaction, UpdateTransaction.Phase.PROJECTED);
		// A journal of an older version proved its publication only by these directories; PROJECTED is durable now.
		PublicationSweep.settleLegacyDirectories(storage);
		List<Lock> held = convergeProjection(transaction);
		if (!held.isEmpty()) return held;
		finalizeModpackState(transaction, preserveNewerSelection);
		probe.reached(Step.FINALIZED);
		return List.of();
	}

	/** One planned byte the apply must prove and pin before a single live file moves. */
	private record Pinned(Path path, String hash, long size, String failureStory) {}

	/** Still the planned before-state, already this apply's after-state, or neither. */
	private enum InFlight {
		BASELINE, DESTINATION, DRIFT
	}

	/** Pin remaining before-bytes of every live operation so a later mutation of this attempt still has them in CAS; a drift here still replans. */
	private void capturePinnedBytes(UpdateTransaction transaction) throws IOException {
		for (Operation operation : transaction.plan().operations()) {
			if (operation.root() == Root.PROJECTION) continue;
			Path live = resolve(operation, transaction);
			InFlight state = inFlight(live, operation);
			if (state == InFlight.DRIFT) throw new UpdateReplanRequiredException(live, driftStory(operation, live));
			if (state == InFlight.DESTINATION || operation.expectedExistingHash() == null) continue;
			acquireImmutable(new Pinned(live, operation.expectedExistingHash(), Files.size(live), driftStory(operation, live)));
		}
	}

	private InFlight inFlight(Path live, Operation operation) throws IOException {
		FileCache.StatSnapshot snapshot = snapshotIfPresent(live);
		boolean atDestination = operation.operation() == OperationType.DELETE
				? snapshot == null
				: snapshot != null && FileIntegrity.observeNamed(live, operation.expectedSize(), operation.expectedObjectHash(), fileCache);
		// The destination wins a tie: an operation whose target already holds its after-state is complete, whatever its
		// baseline was. Its before-bytes are either pinned by the attempt that moved them or are the destination object.
		if (atDestination) return InFlight.DESTINATION;
		return atBaseline(operation, snapshot, live) ? InFlight.BASELINE : InFlight.DRIFT;
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

	private static String driftStory(Operation operation, Path live) {
		String described = operation.root() == Root.OVERLAY ? "Client overlay" : "Game-directory";
		return described + (operation.expectedExistingHash() == null ? " target appeared after planning: " : " target changed after planning: ") + live;
	}

	/** The shared capture body: prove the live bytes are the planned ones, then pin them in the object store. */
	private void acquireImmutable(Pinned pinned) throws IOException {
		if (!FileIntegrity.observe(pinned.path(), pinned.size(), pinned.hash(), fileCache)) throw new UpdateReplanRequiredException(pinned.path(), pinned.failureStory() + ": " + pinned.path());
		VerifiedFileTransfer.copyAtomicImmutable(pinned.path(), storage.objectFile(pinned.hash().toLowerCase(Locale.ROOT)), pinned.size(), pinned.hash(), fileCache);
	}

	/**
	 * Lands every live operation that has not landed, each with one atomic rename or delete, installs before deletes. A
	 * drifted path is written over - this run's history snapshot holds its bytes - and a held one stays owed while the
	 * rest go on. The plan keeps exactly the owed operations, so the journal a deferral persists is the remaining work.
	 */
	private List<Lock> applyOperations(UpdateTransaction transaction, AtomicReference<Operation> current) throws IOException {
		List<Operation> owed = new ArrayList<>();
		List<Lock> locks = new ArrayList<>();
		for (Operation operation : transaction.plan().operations()) {
			if (operation.root() == Root.PROJECTION) {
				owed.add(operation);
				continue;
			}
			current.set(operation);
			Path target = resolve(operation, transaction);
			try {
				if (inFlight(target, operation) == InFlight.DESTINATION) continue;
				probe.beforeWrite(target);
				if (operation.operation() == OperationType.DELETE) {
					Files.delete(target);
					FileTrees.pruneEmptyAncestors(target, storage.root(operation.root(), transaction.plan().modpackId()));
				} else {
					VerifiedFileTransfer.copyAtomic(storage.objectFile(operation.expectedObjectHash()), target, operation.expectedSize(), operation.expectedObjectHash(), fileCache);
				}
				String path = UpdateTransactionValidator.normalizeOperationPath(operation.relativePath());
				if (operation.root() == Root.GAME_DIR && ModpackPathPolicy.isModPath(path)) scannedChanges.add((operation.operation() == OperationType.DELETE ? "deleted " : "wrote ") + path);
			} catch (IOException e) {
				if (!isLockFailure(e)) throw e;
				locks.add(new Lock(operation, target, e));
				owed.add(operation);
			}
		}
		transaction.plan = transaction.plan().withOperations(owed);
		return locks;
	}

	/**
	 * Converges {@code active/} onto the plan's whole final projection from CAS ({@link ProjectionConvergence}): never
	 * an operation diff, so a stacked plan, a replay from any crash point, and an older version's half-published journal
	 * all end in the same tree. A held path stays owed and the projection state stays converging, so no loader gate takes
	 * the mixed tree; the projection state settles with the COMMITTED tail ({@link #finalizeCommitted}).
	 */
	private List<Lock> convergeProjection(UpdateTransaction transaction) throws IOException {
		List<ProjectionState.Row> target = ProjectionConvergence.targetRows(transaction.plan());
		ProjectionConvergence.Result result = new ProjectionConvergence(storage, fileCache, new ProjectionConvergence.Writes() {
			@Override
			public void beforeWrite(Path target) throws IOException {
				probe.beforeWrite(target);
			}

			@Override
			public void marked() throws IOException {
				probe.reached(Step.PROJECTION_MARKED);
			}
		}).converge(transaction.transactionId, target, projectionHints(transaction, target));
		if (result.changed()) noteProjectionChange();
		if (!result.converged()) return result.held().stream().map(held -> new Lock(null, held.path(), held.cause())).toList();
		probe.reached(Step.PROJECTION_CONVERGED);
		return List.of();
	}

	/**
	 * The objects the current projection files are expected to be, so the fallback answers by identity before any hash:
	 * a file the plan does not touch is already its target object, and one it touches is the baseline its operation
	 * names. Anything else - a player's file, a stale one - answers from its cache record.
	 */
	private static Map<String, String> projectionHints(UpdateTransaction transaction, List<ProjectionState.Row> target) throws IOException {
		Map<String, String> hints = new HashMap<>();
		for (ProjectionState.Row row : target) hints.put(row.path(), row.sha1());
		for (Operation operation : transaction.plan().operations()) {
			if (operation.root() != Root.PROJECTION) continue;
			String path = UpdateTransactionValidator.normalizeOperationPath(operation.relativePath());
			if (operation.expectedExistingHash() != null) hints.put(path, operation.expectedExistingHash());
			else hints.remove(path);
		}
		return hints;
	}

	/** A directory-scanning loader that already took {@code active/} this boot learns of the change through the scanned changes (P5). */
	private void noteProjectionChange() {
		if (ClientStorage.directoryScanRevision() != null && !scannedChanges.contains(PROJECTION_SCANNED_CHANGE)) scannedChanges.add(PROJECTION_SCANNED_CHANGE);
	}

	/**
	 * The durable finalization: planned config, selection, launcher switch, and pack state with its active-state pointer. The
	 * planned config is rebased on the stored one, so a setting the player changed since planning keeps their value.
	 */
	private void finalizeModpackState(UpdateTransaction transaction, boolean preserveNewerSelection) throws IOException {
		SelectedModpackTarget resolved = validator.resolvedTarget(transaction, validator.targetDocument(transaction));
		if (transaction.plan().plannedClientConfig() != null && !preserveNewerSelection)
			try {
				ClientConfigJsons.ClientConfigFieldsV3 stored = ReconfConfigs.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
						.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
				ReconfConfigs.save(storage.clientConfigFile(), stored.rebase(transaction.expectedClientConfig, transaction.plan().plannedClientConfig()),
						ClientConfigJsons.ClientConfigFieldsV3.class, ClientConfigJsons.ClientConfigFieldsV3::new);
			} catch (IOException e) {
				throw new ConfigTools.ConfigException("Failed to save client configuration", e);
			}
		if (!preserveNewerSelection) storage.writeSelectedModpackId(UpdateTransactionValidator.plannedFollow(transaction));
		applyLauncherSwitch(transaction, resolved);
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			storage.writeActiveState(resolved.document(), resolved.selection().intent());
		} else if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
			GeneratedCopyState.removeGeneration(storage, transaction.plan().modpackId(), transaction.plan().packTarget().contentToken());
			storage.clearActiveState();
		} else {
			storage.clearActiveState();
		}
	}

	/**
	 * The launcher switch lands only here, with the files it belongs to, so the launcher never starts versions the
	 * installed mods were not made for, and a boot replay that changed a record relaunches. The pre-write gate already
	 * proved the metadata writable, so a failure here is the metadata changing under a started apply, which stays owed.
	 */
	private void applyLauncherSwitch(UpdateTransaction transaction, SelectedModpackTarget resolved) throws IOException {
		LauncherVersionSwapper.Switch launcherSwitch = transaction.plan().launcherSwitch(resolved.flatTarget());
		if (launcherSwitch != null && LauncherVersionSwapper.apply(launcherSwitch, storage.gameDirectory())) scannedChanges.add("wrote launcher metadata");
	}

	/** The gate before the first write: a switch whose launcher metadata is gone or unreadable replans instead of landing files the launcher cannot follow. */
	private void requireLauncherWritable(UpdateTransaction transaction) throws IOException {
		LauncherVersionSwapper.Switch launcherSwitch = transaction.plan().launcherSwitch(validator.resolvedTarget(transaction, validator.targetDocument(transaction)).flatTarget());
		if (launcherSwitch == null) return;
		try {
			LauncherVersionSwapper.requireWritable(launcherSwitch, storage.gameDirectory());
		} catch (IOException e) {
			throw new UpdateReplanRequiredException(null, "Launcher metadata for the planned version switch is unusable: " + e.getMessage(), e);
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
			ConfigTools.writeAtomicCompact(storage.transactionFile(), transaction);
		} catch (IOException journalFailure) {
			cause.addSuppressed(journalFailure);
		}
	}

	/** Marks an in-memory phase transition. The phase is only durable at the boundaries: the PLANNED intent, the PREPARING first-write marker, the COMMITTED marker, and the result receipt. */
	private void setPhase(UpdateTransaction transaction, UpdateTransaction.Phase phase) {
		transaction.phase = phase;
	}

	/** Persists the journal at a durable phase boundary, rewriting the whole record exactly where recovery depends on the phase. */
	private void persistPhase(UpdateTransaction transaction, UpdateTransaction.Phase phase) throws IOException {
		setPhase(transaction, phase);
		ConfigTools.writeAtomicCompact(storage.transactionFile(), transaction);
	}

	private boolean publicationStarted(UpdateTransaction transaction) {
		return ClientProjectionView.publicationStarted(storage, transaction);
	}

	private boolean mutationStarted(UpdateTransaction transaction) {
		return ClientProjectionView.mutationStarted(storage, transaction);
	}

	/** The recovery triggers' question before the first live write: has any mutable input moved such that the plan must be built again. */
	private boolean replanRequired(UpdateTransaction transaction) throws IOException {
		return validator.mutableInputDrift(transaction, fileCache).any();
	}

	/** The selection gate a fresh commit and an unstarted recovery both run before anything moves. */
	private void verifySelectionUnchanged(UpdateTransaction transaction) throws IOException {
		if (validator.mutableInputDrift(transaction, fileCache).selection()) throw new IOException("Group selection changed after planning for modpack " + transaction.plan().modpackId());
	}

	/**
	 * The tombstone step: a modpack update leaves behind only the tombstones its target still ships as editable,
	 * written the same way by the live apply and every replay, rc journals included.
	 */
	private void applyOverlayTombstones(UpdateTransaction transaction) throws IOException {
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE) return;
		String modpackId = transaction.plan().modpackId();
		ModpackJsons.ModpackContentFields target = validator.resolvedTarget(transaction, validator.targetDocument(transaction)).flatTarget();
		storage.writeOverlayState(modpackId, UpdatePlanner.retainedTombstones(storage.readOverlayState(modpackId).deletedPaths, target));
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

	/**
	 * AccessDeniedException is a sharing violation only on Windows. A generic FileSystemException is a lock on every OS
	 * because the kernel reason is localized (FormatMessage, strerror); named subtypes that cannot be a lock, and the
	 * storage conditions no wait fixes ({@link StorageConditions}: full, read-only, unsupported), stay failures.
	 */
	static boolean isLockFailure(IOException exception, boolean windows) {
		if (StorageConditions.condition(exception) != null) return false;
		Throwable current = exception;
		while (current != null) {
			if (current instanceof AccessDeniedException) return windows;
			if (current instanceof FileSystemException fileSystemException && !definiteNonLock(fileSystemException)) return true;
			current = current.getCause();
		}
		return false;
	}

	private static boolean definiteNonLock(FileSystemException exception) {
		return exception instanceof NoSuchFileException || exception instanceof DirectoryNotEmptyException || exception instanceof FileAlreadyExistsException || exception instanceof NotDirectoryException
				|| exception instanceof AtomicMoveNotSupportedException || exception instanceof FileSystemLoopException || exception instanceof NotLinkException;
	}

	static boolean isLockFailure(IOException exception) {
		return isLockFailure(exception, PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS);
	}
}
