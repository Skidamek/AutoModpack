package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.BaselineCapture;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.ConflictAction;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.PreservationProof;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
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

	public record Context(ClientStorage storage, CommitAction beforeManifestAction) {
		public Context {
			storage = Objects.requireNonNull(storage, "storage");
		}
	}

	public record Execution(UpdateTransaction.Status status, UpdateTransaction transaction, String operation, Path blockedPath, String message) {
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

	public void validate(UpdateTransaction transaction) throws IOException {
		withFileCache(cache -> {
			validator.validate(transaction, null, true, cache);
			return null;
		});
	}

	public Execution commit(UpdatePlan plan, SelectedModpackTarget target) throws IOException {
		return commit(plan, target, context.storage().overlayDigest(plan.modpackId()));
	}

	public Execution commit(UpdatePlan plan, SelectedModpackTarget target, String overlayDigest) throws IOException {
		return commit(plan, target, overlayDigest, readClientConfig());
	}

	public Execution commit(UpdatePlan plan, SelectedModpackTarget target, String overlayDigest,
			ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) throws IOException {
		UpdateTransaction transaction = UpdateTransaction.create(plan, target, overlayDigest, expectedClientConfig);
		return commitPrepared(transaction, target);
	}

	public Execution commit(UpdateTransaction transaction) throws IOException {
		return commitPrepared(transaction, null);
	}

	private Execution commitPrepared(UpdateTransaction transaction, SelectedModpackTarget unpublishedTarget) throws IOException {
		return ClientStorageMutation.run(context.storage(), () -> commitPreparedLocked(transaction, unpublishedTarget));
	}

	private Execution commitPreparedLocked(UpdateTransaction transaction, SelectedModpackTarget unpublishedTarget) throws IOException {
		return withFileCache(cache -> {
			validator.validate(transaction, unpublishedTarget, true, cache);
			validateSelectionBeforeMutation(transaction);
			preparePendingReplacement(transaction);
			if (unpublishedTarget != null)
				new ClientGenerationStore(context.storage()).write(unpublishedTarget.document(), unpublishedTarget.journal());
			ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
			ClientObjectStore.publishOwnership(context.storage());
			return executePersisted(transaction);
		});
	}

	private void preparePendingReplacement(UpdateTransaction replacement) throws IOException {
		ClientStorage storage = context.storage();
		if (Files.exists(storage.repairJournalFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("An offline repair must finish before an update can start");
		UpdateTransaction pending = readPersistedTransaction();
		if (pending == null) return;
		if (pending.phase == UpdateTransaction.Phase.COMMITTED) {
			cleanupTransactionDirectories(pending);
			Files.deleteIfExists(storage.transactionFile());
			return;
		}
		if (pending.purpose == UpdateTransaction.Purpose.SELF_UPDATE || replacement.purpose == UpdateTransaction.Purpose.SELF_UPDATE)
			throw new IOException("A self-update must finish before another update can start");
		validator.validatePendingReplacementEnvelope(pending);
		if (Files.exists(storage.backupProjectionDirectory(), LinkOption.NOFOLLOW_LINKS)
				&& !verifyProjectionQuietly(storage.activeDirectory(), pending.projectedFinalState))
			throw new IOException("A deferred projection publication must finish before its request can be replaced");
		cleanupTransactionDirectories(pending);
	}

	public Execution recover(UpdateTransaction transaction) throws IOException {
		Objects.requireNonNull(transaction, "transaction");
		return ClientStorageMutation.run(context.storage(), () -> recoverPersisted(transaction.transactionId));
	}

	/** Recovers the current mailbox contents, never a transaction captured by an earlier process. */
	public Execution recoverLatest() throws IOException {
		return ClientStorageMutation.run(context.storage(), () -> recoverPersisted(null));
	}

	/** Reports mutable input drift that requires a fresh plan before live mutation can continue. */
	public boolean hasMutableInputDrift(UpdateTransaction transaction) throws IOException {
		return withFileCache(cache -> {
			if (!isModpackTransaction(transaction)) return false;
			boolean configChanged = configurationChangedAfterPlanning(transaction);
			if (projectionPublicationStarted(transaction))
				return configChanged || transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
						&& (!overlayStateMatches(transaction) || selectionChangedAfterPlanning(transaction));
			if (configChanged) return true;
			if (!Objects.equals(transaction.overlayDigest, context.storage().overlayDigest(transaction.modpackId))) return true;
			return selectionChangedAfterPlanning(transaction);
		});
	}

	private boolean configurationChangedAfterPlanning(UpdateTransaction transaction) throws IOException {
		if (!isModpackTransaction(transaction)) return false;
		if (transaction.expectedClientConfig == null) return true;
		ClientConfigJsons.ClientConfigFieldsV3 current = readClientConfig();
		if (current.equals(transaction.expectedClientConfig)) return false;
		return transaction.plannedClientConfig == null || !current.equals(transaction.plannedClientConfig);
	}

	/** Reports whether the live state has already reached the point where only projection publication remains. */
	public boolean projectionPublicationStarted(UpdateTransaction transaction) {
		return ClientProjectionView.publicationStarted(context.storage(), transaction);
	}

	private Execution recoverPersisted(String expectedTransactionId) throws IOException {
		return withFileCache(cache -> {
			UpdateTransaction pending = readPersistedTransaction();
			if (pending == null) return new Execution(UpdateTransaction.Status.SUCCESS, null, null, null, null);
			if (expectedTransactionId != null && !expectedTransactionId.equals(pending.transactionId))
				throw new IOException("The requested update transaction was superseded by a newer pending request");
			boolean publicationStarted = projectionPublicationStarted(pending);
			if (!publicationStarted && hasMutableInputDrift(pending)) throw new UpdateReplanRequiredException(null, "Pending update input changed after planning");
			validator.validateUnchecked(pending, null, !publicationStarted, fileCache);
			if (!publicationStarted) validateSelectionBeforeMutation(pending);
			return executePersisted(pending);
		});
	}

	private boolean selectionChangedAfterPlanning(UpdateTransaction transaction) throws IOException {
		SelectionIntent current = new ClientSelectionStore(context.storage().selectionFile()).get(transaction.modpackId).orElse(null);
		SelectionIntent expected = transaction.expectedPriorIntent();
		boolean alreadyCommitted = transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
				? Objects.equals(current, transaction.targetIntent())
				: transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL ? current == null : Objects.equals(current, expected);
		return !Objects.equals(current, expected) && !alreadyCommitted;
	}

	public UpdateTransaction readPersisted() {
		return ConfigTools.read(context.storage().transactionFile(), UpdateTransaction.class).orElse(null);
	}

	private ClientConfigJsons.ClientConfigFieldsV3 readClientConfig() {
		return ConfigTools.read(context.storage().clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
	}

	private UpdateTransaction readPersistedTransaction() throws IOException {
		if (!Files.exists(context.storage().transactionFile(), LinkOption.NOFOLLOW_LINKS)) return null;
		try {
			return ConfigTools.read(context.storage().transactionFile(), UpdateTransaction.class)
					.orElseThrow(() -> new IOException("Persisted update transaction is missing"));
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Persisted update transaction is invalid", e);
		}
	}

	private Execution executePersisted(UpdateTransaction transaction) throws IOException {
		if (transaction.phase == UpdateTransaction.Phase.COMMITTED) {
			ClientObjectStore.publishOwnership(context.storage());
			cleanupTransactionDirectories(transaction);
			Files.deleteIfExists(context.storage().transactionFile());
			ClientObjectStore.publishOwnership(context.storage());
			return new Execution(UpdateTransaction.Status.SUCCESS, transaction, null, null, null);
		}
		AtomicReference<Operation> current = new AtomicReference<>();
		Path blockedPath = null;
		boolean publicationStarted = projectionPublicationStarted(transaction);
		boolean liveAlreadyApplied = isModpackTransaction(transaction) && (publicationStarted || managedStateMatches(transaction));
		boolean preserveNewerSelection = publicationStarted && selectionChangedAfterPlanning(transaction);
		try {
			transaction.resultStatus = null;
			transaction.resultOperation = null;
			transaction.resultPath = null;
			transaction.resultMessage = null;
			setPhase(transaction, UpdateTransaction.Phase.PREPARING);
			if (isModpackTransaction(transaction)) {
				applyModpackTransaction(transaction, current, publicationStarted, liveAlreadyApplied, preserveNewerSelection);
			} else applyOperations(transaction, current);
			ClientObjectStore.publishOwnership(context.storage());
			setPhase(transaction, UpdateTransaction.Phase.COMMITTED);
			cleanupTransactionDirectories(transaction);
			Files.deleteIfExists(context.storage().transactionFile());
			ClientObjectStore.publishOwnership(context.storage());
			return new Execution(UpdateTransaction.Status.SUCCESS, transaction, null, null, null);
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
				recordResult(transaction, status, operationName, blockedPath, e.getMessage(), e);
				return new Execution(status, transaction, operationName, blockedPath, e.getMessage());
			}
			recordResult(transaction, UpdateTransaction.Status.FAILED, operationName, blockedPath, e.getMessage(), e);
			throw new UpdateExecutionException(operationName, blockedPath, e);
		}
	}

	/** The modpack apply sequence: pre-mutation captures, live operations, projection publication, and durable finalization. */
	private void applyModpackTransaction(UpdateTransaction transaction, AtomicReference<Operation> current, boolean publicationStarted, boolean liveAlreadyApplied,
			boolean preserveNewerSelection) throws IOException {
		if (!publicationStarted && configurationChangedAfterPlanning(transaction))
			throw new UpdateReplanRequiredException(null, "Client configuration changed after planning the update");
		captureBaselines(transaction);
		// The ledger-driven batch is bookkeeping; the conflict resolutions are the player's last review
		// decisions and must become the newest vault claims, which the vault surfaces first.
		preserveBeforeMutation(transaction);
		preserveConflicts(transaction);
		if (!liveAlreadyApplied) applyOperations(transaction, current);
		current.set(null);
		if (!publicationStarted) {
			verifyManagedFinalState(transaction);
			if (selectionChangedAfterPlanning(transaction)) throw new UpdateReplanRequiredException(null, "Group selection changed while applying the update");
			if (configurationChangedAfterPlanning(transaction))
				throw new UpdateReplanRequiredException(null, "Client configuration changed while applying the update");
		}
		publishProjection(transaction);
		if (publicationStarted
				&& (!managedStateMatches(transaction) || preserveNewerSelection || configurationChangedAfterPlanning(transaction)))
			throw new UpdateReplanRequiredException(null, "Mutable client state changed while publishing the update");
		if (selectionChangedAfterPlanning(transaction) || configurationChangedAfterPlanning(transaction))
			throw new UpdateReplanRequiredException(null, "Mutable client configuration changed before update finalization");
		finalizeModpackState(transaction, preserveNewerSelection);
		claimSelection(transaction);
	}

	/** Builds and swaps the incoming projection unless the active tree already matches; no-ops when the projection was published earlier. */
	private void publishProjection(UpdateTransaction transaction) throws IOException {
		if (transaction.operations.isEmpty() && verifyProjectionQuietly(context.storage().activeDirectory(), transaction.projectedFinalState)) return;
		buildIncomingProjection(transaction);
		setPhase(transaction, UpdateTransaction.Phase.PROJECTED);
		setPhase(transaction, UpdateTransaction.Phase.SWAPPING);
		swapProjection(transaction);
	}

	/** The durable finalization: planned config, pack state, active-state pointer, and the before-manifest hook. */
	private void finalizeModpackState(UpdateTransaction transaction, boolean preserveNewerSelection) throws IOException {
		ModpackJsons.ModpackContentFields target = validator.resolvedTarget(transaction, validator.storedRecord(transaction)).flatTarget();
		if (transaction.plannedClientConfig != null && !preserveNewerSelection)
			ConfigTools.writeAtomic(context.storage().clientConfigFile(), transaction.plannedClientConfig);
		if (context.beforeManifestAction() != null && transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE)
			context.beforeManifestAction().run(transaction, target);
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			PackTarget generation = transaction.packTarget();
			GeneratedCopyState.fromFields(transaction.plannedGeneratedCopies).write(context.storage());
			context.storage().writeActiveState(transaction.modpackId, generation.contentToken());
		} else if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
			FileTrees.delete(context.storage().generatedCopiesGenerationDirectory(transaction.modpackId, transaction.contentToken));
			context.storage().clearActiveState();
			Files.deleteIfExists(context.storage().baselineFile(transaction.modpackId));
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

	private boolean isModpackTransaction(UpdateTransaction transaction) {
		return UpdateTransactionValidator.isModpackPurpose(transaction.purpose);
	}

	private void setPhase(UpdateTransaction transaction, UpdateTransaction.Phase phase) throws IOException {
		transaction.phase = phase;
		ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
	}

	private void applyOperations(UpdateTransaction transaction, AtomicReference<Operation> current) throws IOException {
		for (Operation operation : transaction.operations) {
			if (operation.operation() != OperationType.INSTALL_OBJECT || operation.root() == Root.PROJECTION) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (FileIntegrity.matches(target, operation.expectedSize(), operation.expectedObjectHash(), fileCache)) continue;
			verifyExpectedExisting(operation, target);
			Path source = context.storage().objectFile(operation.expectedObjectHash());
			VerifiedFileTransfer.copyAtomic(source, target, operation.expectedSize(), operation.expectedObjectHash(), fileCache);
		}
		for (Operation operation : transaction.operations) {
			if (operation.operation() != OperationType.DELETE || operation.root() == Root.PROJECTION) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				verifyExpectedExisting(operation, target);
				Files.delete(target);
			}
			FileTrees.pruneEmptyAncestors(target, context.storage().root(operation.root(), transaction.modpackId));
		}
	}

	private void verifyExpectedExisting(Operation operation, Path target) throws IOException {
		if (operation.root() == Root.OVERLAY) {
			if (operation.expectedExistingHash() == null) {
				if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new UpdateReplanRequiredException(target, "Client overlay target appeared after planning: " + target);
			} else
				if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
						|| !FileIntegrity.matches(target, Files.size(target), operation.expectedExistingHash(), fileCache))
					throw new UpdateReplanRequiredException(target, "Client overlay target changed after planning: " + target);
			return;
		}
		if (operation.root() != Root.GAME_DIR) return;
		if (operation.expectedExistingHash() == null) {
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new UpdateReplanRequiredException(target, "Game-directory target appeared after planning: " + target);
			return;
		}
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
				|| !FileIntegrity.matches(target, Files.size(target), operation.expectedExistingHash(), fileCache))
			throw new UpdateReplanRequiredException(target, "Game-directory target changed after planning: " + target);
	}

	private void preserveBeforeMutation(UpdateTransaction transaction) throws IOException {
		for (Preservation preservation : transaction.plannedPreservations) {
			PreservationOrigin origin = preservationOrigin(transaction, preservation);
			PreservationVault.preserve(context.storage(), origin.modpackId(), origin.contentToken(), origin.reason(), preservation.root(),
					preservation.relativePath(), preservation.expectedHash().toLowerCase(Locale.ROOT), preservation.expectedSize());
		}
	}

	private void preserveConflicts(UpdateTransaction transaction) throws IOException {
		for (Conflict conflict : transaction.plannedConflicts)
			if (conflict.action() == ConflictAction.PRESERVE_LOCAL) PreservationVault.preserveConflict(context.storage(), transaction.contentToken, conflict);
	}

	private PreservationOrigin preservationOrigin(UpdateTransaction transaction, Preservation preservation) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields active = context.storage().readActiveState();
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL)
			return new PreservationOrigin(transaction.modpackId, active == null ? transaction.contentToken : HashUtils.normalizeSha1(active.contentToken), PreservationVault.Reason.MODPACK_REMOVAL);
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_DEACTIVATION)
			return new PreservationOrigin(transaction.modpackId, active == null ? transaction.contentToken : HashUtils.normalizeSha1(active.contentToken), PreservationVault.Reason.MODPACK_DEACTIVATION);
		if (preservation.proof() == PreservationProof.ACTIVE_LEDGER && active != null) {
			PreservationVault.Reason reason = transaction.modpackId.equals(active.modpackId) ? PreservationVault.Reason.SERVER_REMOVAL : PreservationVault.Reason.MODPACK_DEACTIVATION;
			return new PreservationOrigin(active.modpackId, HashUtils.normalizeSha1(active.contentToken), reason);
		}
		if (preservation.proof() == PreservationProof.PLAYER_CONSENT)
			return new PreservationOrigin(transaction.modpackId, transaction.contentToken, PreservationVault.Reason.PLAYER_CONSENT);
		return new PreservationOrigin(transaction.modpackId, transaction.contentToken, PreservationVault.Reason.SERVER_REMOVAL);
	}

	private record PreservationOrigin(String modpackId, String contentToken, PreservationVault.Reason reason) {}

	private void verifyManagedFinalState(UpdateTransaction transaction) throws IOException {
		for (ProjectedFile projected : transaction.projectedFinalState) {
			if (projected.root() == Root.PROJECTION) continue;
			Path target = resolve(projected.root(), projected.relativePath(), transaction);
			if (projected.present()) {
				if (!FileIntegrity.matches(target, projected.expectedSize(), projected.expectedHash(), fileCache))
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
		for (ProjectedFile projected : transaction.projectedFinalState) {
			if (projected.root() != Root.OVERLAY) continue;
			expected.put(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath()), projected.present()
					? new UpdatePlan.FileState(projected.expectedHash(), projected.expectedSize(), true)
					: new UpdatePlan.FileState(null, -1, false));
		}
		return expected.equals(ClientOverlaySnapshot.capture(context.storage(), transaction.modpackId, fileCache).files());
	}

	private void buildIncomingProjection(UpdateTransaction transaction) throws IOException {
		Path incoming = context.storage().incomingProjectionDirectory();
		FileTrees.delete(incoming);
		Files.createDirectories(incoming);
		for (ProjectedFile projected : transaction.projectedFinalState) {
			if (projected.root() != Root.PROJECTION || !projected.present()) continue;
			Path source = context.storage().objectFile(projected.expectedHash());
			Path target = incoming.resolve(UpdateTransactionValidator.normalizeOperationPath(projected.relativePath())).normalize();
			if (!target.startsWith(incoming)) throw new IOException("Projection path escapes incoming directory");
			VerifiedFileTransfer.linkAtomic(source, target, projected.expectedSize(), projected.expectedHash(), fileCache);
		}
		verifyProjection(incoming, transaction.projectedFinalState);
	}

	private void swapProjection(UpdateTransaction transaction) throws IOException {
		Path active = context.storage().activeDirectory();
		Path incoming = context.storage().incomingProjectionDirectory();
		Path backup = context.storage().backupProjectionDirectory();
		if (verifyProjectionQuietly(active, transaction.projectedFinalState)) {
			FileTrees.delete(incoming);
			FileTrees.delete(backup);
			return;
		}
		if (!verifyProjectionQuietly(incoming, transaction.projectedFinalState)) buildIncomingProjection(transaction);
		if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
			FileTrees.delete(active);
		} else if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
			FileTrees.moveRecoverableDirectory(active, backup);
		}
		FileTrees.moveRecoverableDirectory(incoming, active);
		verifyProjection(active, transaction.projectedFinalState);
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
				if (expectedFile == null || !FileIntegrity.matches(path, expectedFile.expectedSize(), expectedFile.expectedHash(), fileCache))
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
		FileTrees.delete(context.storage().incomingProjectionDirectory());
		FileTrees.delete(context.storage().backupProjectionDirectory());
	}

	private void captureBaselines(UpdateTransaction transaction) throws IOException {
		if (transaction.plannedBaselineCaptures.isEmpty()) return;
		Path baselinePath = context.storage().baselineFile(transaction.modpackId);
		ClientStorageJsons.ClientBaselineFields baseline = readBaseline(baselinePath, transaction.modpackId);
		Map<String, ClientStorageJsons.ClientBaselineFields.EntryFields> entries = new TreeMap<>();
		for (ClientStorageJsons.ClientBaselineFields.EntryFields entry : baseline.entries) entries.put(entry.logicalPath, entry);
		boolean changed = false;
		for (BaselineCapture capture : transaction.plannedBaselineCaptures) {
			String logicalPath = capture.relativePath();
			if (entries.containsKey(logicalPath)) continue;
			Path source = resolve(capture.root(), capture.relativePath(), transaction);
			ClientStorageJsons.ClientBaselineFields.EntryFields entry = new ClientStorageJsons.ClientBaselineFields.EntryFields();
			entry.logicalPath = logicalPath;
			entry.baselineGenerationId = "";
			if (capture.absent()) {
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Baseline path was expected to be absent: " + source);
				entry.absent = true;
				entry.objectHash = "";
				entry.size = -1;
			} else {
				if (!FileIntegrity.matches(source, capture.expectedSize(), capture.expectedHash(), fileCache)) throw new IOException("Baseline source changed: " + source);
				Path object = context.storage().objectFile(capture.expectedHash());
				VerifiedFileTransfer.copyAtomicImmutable(source, object, capture.expectedSize(), capture.expectedHash(), fileCache);
				entry.objectHash = capture.expectedHash().toLowerCase(Locale.ROOT);
				entry.size = capture.expectedSize();
			}
			entries.put(logicalPath, entry);
			changed = true;
		}
		if (!changed) return;
		baseline.entries = new ArrayList<>(entries.values());
		Files.createDirectories(baselinePath.getParent());
		ConfigTools.writeAtomic(baselinePath, baseline);
	}

	private ClientStorageJsons.ClientBaselineFields readBaseline(Path path, String modpackId) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			ClientStorageJsons.ClientBaselineFields empty = new ClientStorageJsons.ClientBaselineFields();
			empty.modpackId = modpackId;
			return empty;
		}
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Baseline state is not a regular file");
		ClientStorageJsons.ClientBaselineFields baseline = ConfigTools.read(path, ClientStorageJsons.ClientBaselineFields.class).orElseThrow(() -> new IOException("Baseline state is empty"));
		if (baseline.schemaVersion != 1 || !modpackId.equals(baseline.modpackId) || baseline.entries == null) throw new IOException("Baseline state identity is invalid");
		return baseline;
	}

	private void claimSelection(UpdateTransaction transaction) throws IOException {
		if (!isModpackTransaction(transaction)) return;
		ClientSelectionStore selections = new ClientSelectionStore(context.storage().selectionFile());
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) selections.compareAndSet(transaction.modpackId, transaction.expectedPriorIntent(), transaction.targetIntent());
		else if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) selections.remove(transaction.modpackId, transaction.expectedPriorIntent());
	}

	private void validateSelectionBeforeMutation(UpdateTransaction transaction) throws IOException {
		if (!isModpackTransaction(transaction)) return;
		if (selectionChangedAfterPlanning(transaction)) throw new IOException("Group selection changed after planning for modpack " + transaction.modpackId);
	}

	private Path resolve(Operation operation, UpdateTransaction transaction) throws IOException {
		return resolve(operation.root(), operation.relativePath(), transaction);
	}

	private Path resolve(Root root, String relativePath, UpdateTransaction transaction) throws IOException {
		return FileTrees.resolveConfined(context.storage().root(root, transaction.modpackId), UpdateTransactionValidator.normalizeOperationPath(relativePath), "Operation target");
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
		Throwable current = exception;
		while (current != null) {
			// Windows reports an open handle that denies delete sharing as AccessDeniedException, without a lock-specific reason.
			if (current instanceof AccessDeniedException) return true;
			if (current instanceof FileSystemException fileSystemException) {
				String detail = (Objects.toString(fileSystemException.getReason(), "") + " " + Objects.toString(fileSystemException.getMessage(), "")).toLowerCase(Locale.ROOT);
				if (detail.contains("used by another process") || detail.contains("being used by another process") || detail.contains("sharing violation")) return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
