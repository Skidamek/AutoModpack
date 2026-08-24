package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.BaselineCapture;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.ConflictAction;
import pl.skidam.automodpack_core.update.UpdatePlan.FileKey;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.PreservationProof;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;

/** Validates and applies the one journaled client operation plan. */
public final class UpdateTransactionExecutor {
	private static final Comparator<Operation> OPERATION_ORDER = Comparator.comparing((Operation operation) -> operation.operation().ordinal())
			.thenComparing(operation -> operation.root().ordinal()).thenComparing(Operation::relativePath);
	private final Context context;

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
		validate(transaction, unpublishedTarget);
		validateSelectionBeforeMutation(transaction);
		preparePendingReplacement(transaction);
		if (unpublishedTarget != null)
			new ClientGenerationStore(context.storage()).write(unpublishedTarget.generationRecord(), unpublishedTarget.patchNotesHistory(), unpublishedTarget.historyIndex());
		ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
		ClientObjectStore.publishOwnership(context.storage());
		return executePersisted(transaction);
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
		validatePendingReplacementEnvelope(pending);
		if (Files.exists(storage.backupProjectionDirectory(), LinkOption.NOFOLLOW_LINKS)
				&& !verifyProjectionQuietly(storage.activeDirectory(), pending.projectedFinalState))
			throw new IOException("A deferred projection publication must finish before its request can be replaced");
		cleanupTransactionDirectories(pending);
	}

	private void validatePendingReplacementEnvelope(UpdateTransaction pending) throws IOException {
		try {
			if (pending.schemaVersion != UpdateTransaction.CURRENT_SCHEMA_VERSION) throw new IOException("Unsupported deferred transaction schema");
			UUID.fromString(pending.transactionId);
			if (!isModpackPurpose(pending.purpose) || pending.projectedFinalState == null)
				throw new IOException("Deferred transaction envelope is incomplete");
			ModpackId.requireValid(pending.modpackId);
			validateFinalState(pending.projectedFinalState, pending.modpackId, pending.purpose);
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Deferred transaction envelope is invalid", e);
		}
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
		if (!isModpackTransaction(transaction)) return false;
		boolean configChanged = configurationChangedAfterPlanning(transaction);
		if (projectionPublicationStarted(transaction))
			return configChanged || transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
					&& (!overlayStateMatches(transaction) || hasNewerSelection(transaction));
		if (configChanged) return true;
		if (!Objects.equals(transaction.overlayDigest, context.storage().overlayDigest(transaction.modpackId))) return true;
		return hasNewerSelection(transaction);
	}

	private boolean configurationChangedAfterPlanning(UpdateTransaction transaction) throws IOException {
		if (!isModpackTransaction(transaction)) return false;
		if (transaction.expectedClientConfig == null) return true;
		ClientConfigJsons.ClientConfigFieldsV3 current = readClientConfig();
		if (current.equals(transaction.expectedClientConfig)) return false;
		return transaction.plannedClientConfig == null || !current.equals(transaction.plannedClientConfig);
	}

	private boolean hasNewerSelection(UpdateTransaction transaction) throws IOException {
		if (!isModpackTransaction(transaction)) return false;
		return selectionChangedAfterPlanning(transaction);
	}

	/** Reports whether the live state has already reached the point where only projection publication remains. */
	public boolean projectionPublicationStarted(UpdateTransaction transaction) {
		return ClientProjectionView.publicationStarted(context.storage(), transaction);
	}

	private Execution recoverPersisted(String expectedTransactionId) throws IOException {
		UpdateTransaction pending = readPersistedTransaction();
		if (pending == null) return new Execution(UpdateTransaction.Status.SUCCESS, null, null, null, null);
		if (expectedTransactionId != null && !expectedTransactionId.equals(pending.transactionId))
			throw new IOException("The requested update transaction was superseded by a newer pending request");
		boolean publicationStarted = projectionPublicationStarted(pending);
		if (!publicationStarted && hasMutableInputDrift(pending)) throw new UpdateReplanRequiredException(null, "Pending update input changed after planning");
		validateUnchecked(pending, null, !publicationStarted);
		if (!publicationStarted) validateSelectionBeforeMutation(pending);
		return executePersisted(pending);
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

	public void validate(UpdateTransaction transaction) throws IOException {
		validate(transaction, null);
	}

	private void validate(UpdateTransaction transaction, SelectedModpackTarget selectedTarget) throws IOException {
		try {
			validateUnchecked(transaction, selectedTarget, true);
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Invalid update transaction", e);
		}
	}

	private void validateUnchecked(UpdateTransaction transaction, SelectedModpackTarget selectedTarget, boolean verifyMutableInputs) throws IOException {
		if (transaction == null) throw new IOException("Transaction is missing");
		if (transaction.schemaVersion != UpdateTransaction.CURRENT_SCHEMA_VERSION) throw new IOException("Unsupported transaction schema");
		try {
			UUID.fromString(transaction.transactionId);
		} catch (RuntimeException e) {
			throw new IOException("Invalid transaction UUID", e);
		}
		if (transaction.purpose == null || transaction.phase == null) throw new IOException("Transaction purpose or phase is missing");
		if (transaction.operations == null || transaction.projectedFinalState == null || transaction.restartReasons == null
				|| transaction.plannedPreservations == null || transaction.plannedBaselineCaptures == null || transaction.plannedConflicts == null)
			throw new IOException("Transaction fields are incomplete");
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE && transaction.plannedGeneratedCopies == null)
			throw new IOException("Generated-copy state is missing from modpack update transaction");
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && transaction.plannedGeneratedCopies != null)
			throw new IOException("Generated-copy state is only valid for modpack update transactions");

		ModpackJsons.ModpackContentFields target = null;
		if (isModpackPurpose(transaction.purpose)) {
			if (transaction.expectedClientConfig == null) throw new IOException("Expected client configuration is missing");
			ModpackId.requireValid(transaction.modpackId);
			if (selectedTarget != null && transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE)
				throw new IOException("A supplied update target is only valid for a modpack update transaction");
			GenerationRecord record = selectedTarget == null ? storedRecord(transaction) : selectedTarget.generationRecord();
			target = selectedTarget == null ? resolvedTarget(transaction, record).flatTarget() : selectedTarget.flatTarget();
			if (selectedTarget != null) validateSelectedTargetMetadata(transaction, selectedTarget);
			validateGenerationIdentity(transaction, record, target);
			validateManifest(target, transaction.modpackId);
			validateSelectionMetadata(transaction);
			if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) validateGeneratedCopies(transaction);
			validateStoredClientState(transaction, record);
			if (transaction.plannedClientConfig == null) throw new IOException("Planned client config is missing");
			if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) validatePlannedClientConfig(transaction);
			else validateRemovalClientConfig(transaction);
			if (verifyMutableInputs && !Objects.equals(transaction.overlayDigest, context.storage().overlayDigest(transaction.modpackId)))
				throw new IOException("Client editable overlay changed after planning");
		} else if (transaction.purpose == UpdateTransaction.Purpose.SELF_UPDATE) validateSelfUpdateMetadata(transaction);
		else throw new IOException("Unsupported transaction purpose");

		Map<FileKey, ProjectedFile> finalState = validateFinalState(transaction.projectedFinalState, transaction.modpackId, transaction.purpose);
		for (Operation operation : transaction.operations)
			if (operation == null || operation.root() == null || operation.operation() == null || operation.relativePath() == null)
				throw new IOException("Incomplete transaction operation");
		List<Operation> sortedOperations = transaction.operations.stream().sorted(OPERATION_ORDER).toList();
		if (!transaction.operations.equals(sortedOperations)) throw new IOException("Transaction operations are not deterministically ordered");
		Set<FileKey> operationKeys = new HashSet<>();
		Set<Path> operationTargets = new HashSet<>();
		for (Operation operation : transaction.operations) {
			if (operation == null || operation.root() == null || operation.operation() == null) throw new IOException("Incomplete transaction operation");
			validatePurposeOperation(transaction.purpose, operation);
			String relative = normalizeOperationPath(operation.relativePath());
			FileKey key = new FileKey(operation.root(), relative);
			if (!operationKeys.add(key)) throw new IOException("Duplicate transaction operation target");
			Path physicalTarget = validateRootAndPath(operation.root(), relative, transaction.modpackId, transaction.purpose);
			if (!operationTargets.add(physicalTarget)) throw new IOException("Transaction operations alias the same physical target");
			ProjectedFile projected = finalState.get(key);
			if (projected == null && operation.root() != Root.PROJECTION) throw new IOException("Operation target is missing from projected final state");
			switch (operation.operation()) {
				case INSTALL_OBJECT -> validateInstall(operation, projected);
				case DELETE -> validateDelete(operation, projected);
			}
		}
		validateBaselineCaptures(transaction);
		validateConflicts(transaction, finalState, target);
		validatePreservations(transaction, finalState, target);
		if (transaction.purpose == UpdateTransaction.Purpose.SELF_UPDATE && !operationKeys.equals(finalState.keySet()))
			throw new IOException("Special-purpose transaction operations and projected final state must match exactly");
		if (target != null && transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) validateManifestProjection(target, finalState);
	}

	private static void validateSelectedTargetMetadata(UpdateTransaction transaction, SelectedModpackTarget target) throws IOException {
		if (!Objects.equals(transaction.expectedPriorIntent(), target.expectedPriorIntent()) || !transaction.targetIntent().equals(target.selection().intent())
				|| !transaction.platform().equals(target.platform()))
			throw new IOException("Transaction selection metadata disagrees with the supplied target");
	}

	private void validateGeneratedCopies(UpdateTransaction transaction) throws IOException {
		try {
			GeneratedCopyState state = GeneratedCopyState.fromFields(transaction.plannedGeneratedCopies);
			if (!transaction.modpackId.equals(state.modpackId()) || !transaction.targetGenerationId.equals(state.generationId())
					|| !transaction.selectionDigest.equals(state.selectionDigest()))
				throw new IOException("Generated-copy state identity does not match transaction");
		} catch (RuntimeException e) {
			throw new IOException("Generated-copy state is invalid", e);
		}
	}

	private GenerationRecord storedRecord(UpdateTransaction transaction) throws IOException {
		try {
			return new ClientGenerationStore(context.storage()).read(transaction.targetGenerationId)
					.orElseThrow(() -> new IOException("Client generation record is missing: " + transaction.targetGenerationId));
		} catch (RuntimeException e) {
			throw new IOException("Client generation record is invalid", e);
		}
	}

	private SelectedModpackTarget resolvedTarget(UpdateTransaction transaction, GenerationRecord record) throws IOException {
		try {
			SelectionIntent expected = transaction.expectedPriorIntent();
			ModpackJsons.CompleteModpackContentFields fields = new ClientGenerationStore(context.storage()).readFields(transaction.targetGenerationId)
					.orElseThrow(() -> new IOException("Client generation record is missing: " + transaction.targetGenerationId));
			if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && expected == null)
				return SelectedModpackTarget.prepareDefault(fields, transaction.platform());
			return SelectedModpackTarget.prepare(fields, expected, transaction.targetIntent(), transaction.platform());
		} catch (RuntimeException e) {
			throw new IOException("Client generation selection is invalid", e);
		}
	}

	private void validateGenerationIdentity(UpdateTransaction transaction, GenerationRecord record, ModpackJsons.ModpackContentFields target) throws IOException {
		GenerationTarget transactionTarget;
		try {
			transactionTarget = transaction.generationTarget();
		} catch (RuntimeException e) {
			throw new IOException("Transaction generation identity is invalid", e);
		}
		GenerationTarget recordTarget = GenerationTarget.from(record);
		GenerationTarget flatTarget = GenerationTarget.fromFlat(target);
		if (!transactionTarget.equals(recordTarget) || !transactionTarget.equals(flatTarget))
			throw new IOException("Transaction, generation record, and selected target identities disagree");
		if (!transaction.modpackId.equals(record.manifest().modpackId())) throw new IOException("Generation record belongs to another modpack lineage");
		try {
			if (!OwnershipLedger.fromFields(target.ownershipLedger).equals(record.ownershipLedger())) throw new IOException("Selected target ledger disagrees with generation record");
		} catch (RuntimeException e) {
			throw new IOException("Selected target ledger is invalid", e);
		}
	}

	private void validateStoredClientState(UpdateTransaction transaction, GenerationRecord targetRecord) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = context.storage().readActiveState();
		if (state == null) return;
		if (!ModpackId.isValid(state.modpackId)) throw new IOException("Active client state modpack ID is invalid");
		if (!HashUtils.isSha1(state.generationId))
			throw new IOException("Active client state identity is invalid");
		GenerationRecord stateRecord = new ClientGenerationStore(context.storage()).read(state.generationId)
				.orElseThrow(() -> new IOException("Active client generation record is missing: " + state.generationId));
		if (!state.modpackId.equals(stateRecord.manifest().modpackId())) throw new IOException("Active client state and record belong to different modpacks");
		if (state.modpackId.equals(transaction.modpackId) && state.generationId.equals(targetRecord.metadata().generationId())) {
			if (!stateRecord.equals(targetRecord)) throw new IOException("Active client state disagrees with its generation record");
		}
	}

	private void validateSelectionMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.targetPlatform == null || transaction.expectedPriorRequestedGroups == null || transaction.expectedPriorRequestedCategories == null
				|| transaction.expectedPriorExcludedGroups == null || transaction.requestedGroups == null || transaction.requestedCategories == null || transaction.excludedGroups == null)
			throw new IOException("Selection metadata is incomplete");
		if (!isCanonicalIntentList(transaction.expectedPriorRequestedGroups)
				|| !isCanonicalIntentList(transaction.expectedPriorRequestedCategories)
				|| !isCanonicalIntentList(transaction.expectedPriorExcludedGroups)
				|| !isCanonicalIntentList(transaction.requestedGroups) || !isCanonicalIntentList(transaction.requestedCategories)
				|| !isCanonicalIntentList(transaction.excludedGroups))
			throw new IOException("Selection metadata is not canonical");
		try {
			if (!transaction.selectionDigest.equals(UpdateTransaction.digest(transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
					? transaction.targetIntent()
					: transaction.expectedPriorIntent())))
				throw new IOException("Selection digest does not match selection metadata");
			if (!ClientPlatform.parse(transaction.targetPlatform).id().equals(transaction.targetPlatform)) throw new IOException("Selection platform is not canonical");
		} catch (RuntimeException e) {
			throw new IOException("Selection metadata is invalid", e);
		}
	}

	private void validatePlannedClientConfig(UpdateTransaction transaction) throws IOException {
		ClientConfigJsons.ClientConfigFieldsV3 config = transaction.plannedClientConfig;
		if (config == null || !transaction.modpackId.equals(config.selectedModpackId))
			throw new IOException("Planned client config does not select the transaction modpack");
	}

	private void validateRemovalClientConfig(UpdateTransaction transaction) throws IOException {
		ClientConfigJsons.ClientConfigFieldsV3 config = transaction.plannedClientConfig;
		if (config == null || transaction.modpackId.equals(config.selectedModpackId))
			throw new IOException("Removal client config still selects the removed modpack");
	}

	private static void validateSelfUpdateMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.modpackId != null || transaction.targetGenerationId != null || transaction.parentGenerationId != null || transaction.stateDigest != null
				|| transaction.ledgerDigest != null || transaction.targetPlatform != null || transaction.selectionDigest != null || transaction.overlayDigest != null
				|| transaction.expectedClientConfig != null
				|| transaction.expectedPriorSelectionPresent || transaction.expectedPriorRequestedGroups != null || transaction.expectedPriorRequestedCategories != null
				|| transaction.expectedPriorExcludedGroups != null || transaction.requestedGroups != null || transaction.requestedCategories != null || transaction.excludedGroups != null
				|| transaction.plannedClientConfig != null || transaction.plannedGeneratedCopies != null || !transaction.restartReasons.isEmpty() || !transaction.plannedPreservations.isEmpty()
				|| !transaction.plannedBaselineCaptures.isEmpty() || !transaction.plannedConflicts.isEmpty())
			throw new IOException("Self-update transaction contains modpack metadata");
		long installs = transaction.operations.stream().filter(operation -> operation.operation() == OperationType.INSTALL_OBJECT).count();
		long deletions = transaction.operations.stream().filter(operation -> operation.operation() == OperationType.DELETE).count();
		if (installs != 1 || deletions > 1 || transaction.operations.size() != installs + deletions)
			throw new IOException("Self-update transaction must contain one install and at most one deletion");
	}

	private static void validatePurposeOperation(UpdateTransaction.Purpose purpose, Operation operation) throws IOException {
		if (purpose == UpdateTransaction.Purpose.SELF_UPDATE) {
			if (operation.root() != Root.GAME_DIR || (operation.operation() != OperationType.INSTALL_OBJECT && operation.operation() != OperationType.DELETE))
				throw new IOException("Self-update operations are restricted to JAR replacement in the mods directory");
			Path relative = Path.of(normalizeOperationPath(operation.relativePath()));
			if (relative.getNameCount() != 2 || !relative.getName(0).toString().equalsIgnoreCase(ModpackPathPolicy.MODS_ROOT)
					|| !JarUtils.hasJarExtension(relative))
				throw new IOException("Self-update target must be a direct JAR child of the mods directory");
		} else if (isModpackPurpose(purpose)) {
			if (operation.root() != Root.PROJECTION && operation.root() != Root.OVERLAY && operation.root() != Root.GAME_DIR)
				throw new IOException("Modpack operations are restricted to projection, overlays, and managed live files");
		} else throw new IOException("Unsupported transaction purpose");
	}

	private void validateManifest(ModpackJsons.ModpackContentFields manifest, String modpackId) throws IOException {
		if (manifest == null || manifest.list == null || manifest.selectedGroups == null || !modpackId.equals(manifest.modpackId) || !ModpackId.isValid(manifest.modpackId))
			throw new IOException("Selected target identity is invalid");
		Set<String> normalizedPaths = new HashSet<>();
		for (var item : manifest.list) {
			if (item == null || item.type == null || item.type.isBlank()) throw new IOException("Manifest item is incomplete");
			String relative = normalizeManifestPath(item.file);
			if (!normalizedPaths.add(relative)) throw new IOException("Manifest contains duplicate normalized path: " + relative);
			parseNonnegativeSize(item.size);
			validateHash(item.sha1, "manifest SHA-1");
		}
		try {
			OwnershipLedger ledger = OwnershipLedger.fromFields(manifest.ownershipLedger);
			if (!modpackId.equals(ledger.modpackId())) throw new IOException("Manifest ledger identity is invalid");
		} catch (RuntimeException e) {
			throw new IOException("Manifest ownership ledger is invalid", e);
		}
	}

	private static boolean isCanonicalIntentList(List<String> values) {
		return values != null && values.equals(values.stream().distinct().sorted().toList());
	}

	private Map<FileKey, ProjectedFile> validateFinalState(List<ProjectedFile> entries, String modpackId, UpdateTransaction.Purpose purpose) throws IOException {
		Map<FileKey, ProjectedFile> finalState = new LinkedHashMap<>();
		Set<Path> physicalTargets = new HashSet<>();
		FileKey previous = null;
		for (ProjectedFile entry : entries) {
			if (entry == null || entry.root() == null) throw new IOException("Incomplete projected final-state entry");
			if (purpose == UpdateTransaction.Purpose.SELF_UPDATE) {
				Path selfUpdatePath = Path.of(normalizeOperationPath(entry.relativePath()));
				if (entry.root() != Root.GAME_DIR || selfUpdatePath.getNameCount() != 2 || !selfUpdatePath.getName(0).toString().equalsIgnoreCase(ModpackPathPolicy.MODS_ROOT)
						|| !JarUtils.hasJarExtension(selfUpdatePath))
					throw new IOException("Self-update projected state is restricted to direct JAR children of the mods directory");
			}
			String relative = normalizeOperationPath(entry.relativePath());
			Path physicalTarget = validateRootAndPath(entry.root(), relative, modpackId, purpose);
			if (!physicalTargets.add(physicalTarget)) throw new IOException("Projected entries alias the same physical target");
			FileKey key = new FileKey(entry.root(), relative);
			if (previous != null && compareFileKeys(previous, key) >= 0) throw new IOException("Projected final state is not uniquely ordered");
			previous = key;
			if (entry.present()) {
				validateHash(entry.expectedHash(), "projected SHA-1");
				if (entry.expectedSize() < 0) throw new IOException("Invalid projected file size");
			} else if (entry.expectedHash() != null || entry.expectedSize() != -1) throw new IOException("Projected absence has file metadata");
			finalState.put(key, entry);
		}
		return finalState;
	}

	private void validateBaselineCaptures(UpdateTransaction transaction) throws IOException {
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE) {
			if (!transaction.plannedBaselineCaptures.isEmpty()) throw new IOException("Only modpack updates can capture baselines");
			return;
		}
		for (BaselineCapture capture : transaction.plannedBaselineCaptures)
			if (capture == null || capture.root() == null || capture.relativePath() == null || capture.expectedHash() == null)
				throw new IOException("Invalid baseline capture");
		List<BaselineCapture> sorted = transaction.plannedBaselineCaptures.stream().sorted(Comparator.comparing((BaselineCapture capture) -> capture.root().ordinal())
				.thenComparing(BaselineCapture::relativePath)).toList();
		if (!transaction.plannedBaselineCaptures.equals(sorted)) throw new IOException("Baseline captures are not deterministically ordered");
		for (BaselineCapture capture : transaction.plannedBaselineCaptures) {
			if (capture == null || capture.root() != Root.GAME_DIR) throw new IOException("Invalid baseline capture");
			String relative = normalizeOperationPath(capture.relativePath());
			validateRootAndPath(capture.root(), relative, transaction.modpackId, transaction.purpose);
			if (capture.absent()) {
				if (!capture.expectedHash().isEmpty() || capture.expectedSize() != -1) throw new IOException("Absent baseline contains file metadata");
			} else {
				validateHash(capture.expectedHash(), "baseline SHA-1");
				if (capture.expectedSize() < 0) throw new IOException("Invalid baseline size");
			}
		}
	}

	private void validatePreservations(UpdateTransaction transaction, Map<FileKey, ProjectedFile> finalState, ModpackJsons.ModpackContentFields target) throws IOException {
		boolean removal = transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL || transaction.purpose == UpdateTransaction.Purpose.MODPACK_DEACTIVATION;
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && !removal) {
			if (!transaction.plannedPreservations.isEmpty()) throw new IOException("Only modpack transactions can preserve deleted files");
			return;
		}
		if (target == null) throw new IOException("Preservation validation has no target");
		if (transaction.plannedPreservations.isEmpty()) return;
		for (Preservation preservation : transaction.plannedPreservations)
			if (preservation == null || preservation.root() == null || preservation.relativePath() == null || preservation.expectedHash() == null || preservation.proof() == null)
				throw new IOException("Invalid preservation");
		OwnershipLedger activeLedger = cleanupLedger();
		OwnershipLedger targetLedger = OwnershipLedger.fromFields(target.ownershipLedger);
		ClientStorageJsons.ClientGenerationStateFields activeState = context.storage().readActiveState();
		Set<String> targetPaths = new HashSet<>();
		for (var item : target.list) targetPaths.add(normalizeManifestPath(item.file));
		List<Preservation> sorted = transaction.plannedPreservations.stream().sorted(Comparator.comparing((Preservation preservation) -> preservation.root().ordinal())
				.thenComparing(Preservation::relativePath).thenComparing(Preservation::expectedHash).thenComparingLong(Preservation::expectedSize)).toList();
		if (!transaction.plannedPreservations.equals(sorted)) throw new IOException("Preservations are not deterministically ordered");
		Set<FileKey> preservationKeys = new HashSet<>();
		for (Preservation preservation : sorted) {
			if (preservation == null || preservation.root() != Root.GAME_DIR)
				throw new IOException("Invalid preservation root");
			if (preservation.proof() == null) throw new IOException("Preservation proof is missing");
			String relative = normalizeOperationPath(preservation.relativePath());
			validateRootAndPath(preservation.root(), relative, transaction.modpackId, transaction.purpose);
			if (!preservationKeys.add(new FileKey(preservation.root(), relative))) throw new IOException("Duplicate preservation target");
			validateHash(preservation.expectedHash(), "preservation SHA-1");
			String logicalPath = relative;
			if (preservation.proof() == PreservationProof.PLAYER_CONSENT) {
				if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE
						|| Path.of(relative).getNameCount() != 2 || !Path.of(relative).getName(0).toString().equals(ModpackPathPolicy.MODS_ROOT))
					throw new IOException("Player-consent preservation is restricted to direct mods files on first install");
				Operation deletion = transaction.operations.stream().filter(operation -> operation.root() == Root.GAME_DIR
						&& operation.operation() == OperationType.DELETE && relative.equals(operation.relativePath())).findFirst().orElse(null);
				Operation installation = transaction.operations.stream().filter(operation -> operation.root() == Root.GAME_DIR
						&& operation.operation() == OperationType.INSTALL_OBJECT && relative.equals(operation.relativePath())).findFirst().orElse(null);
				boolean deleteProof = deletion != null && deletion.expectedExistingHash() != null && preservation.expectedHash().equalsIgnoreCase(deletion.expectedExistingHash());
				boolean replaceProof = installation != null && installation.expectedExistingHash() != null
						&& preservation.expectedHash().equalsIgnoreCase(installation.expectedExistingHash());
				if (!deleteProof && !replaceProof) throw new IOException("Player-consent preservation has no matching hash-pinned operation");
				if (activeState != null && !(activeState.modpackId.equals(transaction.modpackId) && activeState.generationId.equals(transaction.targetGenerationId)
						&& hasPlayerConsentClaim(transaction, preservation, relative)))
					throw new IOException("Player-consent preservation is only valid on a first install");
				ProjectedFile projected = finalState.get(new FileKey(preservation.root(), relative));
				if (projected == null || (deleteProof && projected.present()) || (replaceProof && !projected.present()))
					throw new IOException("Player-consent preservation target has an invalid projected final state");
				continue;
			}
			if (!removal && targetPaths.contains(logicalPath)) throw new IOException("Preservation target remains in the selected target");
			OwnershipLedger ledger = preservation.proof() == PreservationProof.ACTIVE_LEDGER ? activeLedger : targetLedger;
			if (ledger == null) throw new IOException("Preservation has no active ownership ledger");
			OwnershipLedger.Entry ledgerEntry = ledger.entries().get(logicalPath);
			if (ledgerEntry == null) throw new IOException("Preservation path is not present in the ownership ledger");
			if (preservation.proof() == PreservationProof.SERVER_LEDGER && ledgerEntry.currentStatus() != OwnershipLedger.Status.TOMBSTONE)
				throw new IOException("Server-ledger preservation is not a tombstone");
			if (!ledgerEntry.historicalHashes().contains(new OwnershipLedger.Content(preservation.expectedHash().toLowerCase(Locale.ROOT), preservation.expectedSize())))
				throw new IOException("Preservation target is not owned by the target ledger");
			ProjectedFile projected = finalState.get(new FileKey(preservation.root(), relative));
			if (projected == null) throw new IOException("Preservation target is missing from projected final state");
			if (projected.present() && preservation.expectedHash().equalsIgnoreCase(projected.expectedHash()) && preservation.expectedSize() == projected.expectedSize())
				throw new IOException("Preserved bytes remain in projected final state");
		}
	}

	private boolean hasPlayerConsentClaim(UpdateTransaction transaction, Preservation preservation, String relative) throws IOException {
		return PreservationVault.read(context.storage(), transaction.modpackId).claims().stream().anyMatch(claim -> claim.sourceRoot() == Root.GAME_DIR
				&& claim.originalPath().equals(relative) && claim.objectHash().equalsIgnoreCase(preservation.expectedHash()) && claim.size() == preservation.expectedSize()
				&& claim.reason() == PreservationVault.Reason.PLAYER_CONSENT && claim.generationId().equals(transaction.targetGenerationId));
	}

	private void validateConflicts(UpdateTransaction transaction, Map<FileKey, ProjectedFile> finalState, ModpackJsons.ModpackContentFields target) throws IOException {
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE) {
			if (!transaction.plannedConflicts.isEmpty()) throw new IOException("Only modpack updates may contain conflicts");
			return;
		}
		if (target == null) throw new IOException("Conflict validation has no target");
		for (Conflict conflict : transaction.plannedConflicts) {
			if (conflict == null) throw new IOException("Conflict identity is invalid");
			try {
				conflict.validate();
			} catch (RuntimeException e) {
				throw new IOException("Conflict metadata is invalid", e);
			}
		}
		List<Conflict> sorted = transaction.plannedConflicts.stream().sorted(Comparator.comparing(Conflict::conflictId)).toList();
		if (!transaction.plannedConflicts.equals(sorted)) throw new IOException("Conflicts are not deterministically ordered");
		OwnershipLedger activeLedger = cleanupLedger();
		PreservationVault.read(context.storage(), transaction.modpackId);
		Set<String> targetPaths = new HashSet<>();
		for (var item : target.list) targetPaths.add(normalizeManifestPath(item.file));
		Set<String> conflictIds = new HashSet<>();
		for (Conflict conflict : sorted) {
			if (conflict == null || conflict.action() == null || !transaction.modpackId.equals(conflict.modpackId()) || !conflictIds.add(conflict.conflictId()))
				throw new IOException("Conflict identity is invalid");
			if (!ModpackPathPolicy.isModPath(conflict.sourcePath()) || !ModpackPathPolicy.isModPath(conflict.targetPath()) || !targetPaths.contains(conflict.targetPath()))
				throw new IOException("Conflict path is outside the selected target mods");
			validateHash(conflict.sourceHash(), "conflict source SHA-1");
			validateHash(conflict.targetHash(), "conflict target SHA-1");
			if (conflict.sourceSize() < 0 || conflict.targetSize() < 0 || conflict.modIds().isEmpty()) throw new IOException("Conflict content metadata is invalid");
			FileKey sourceKey = new FileKey(Root.GAME_DIR, conflict.sourcePath());
			Operation sourceOperation = null;
			for (Operation operation : transaction.operations)
				if (operation.root() == Root.GAME_DIR && conflict.sourcePath().equals(normalizeOperationPath(operation.relativePath()))) {
					sourceOperation = operation;
					break;
				}
			if (sourceOperation == null || (sourceOperation.operation() == OperationType.DELETE && sourceOperation.expectedExistingHash() == null)
					|| (sourceOperation.operation() == OperationType.INSTALL_OBJECT && (!conflict.sourcePath().equals(conflict.targetPath()) || sourceOperation.expectedExistingHash() == null)))
				throw new IOException("Conflict has no ownership-safe source operation");
			if (sourceOperation.expectedExistingHash() != null && !sourceOperation.expectedExistingHash().equalsIgnoreCase(conflict.sourceHash()))
				throw new IOException("Conflict source operation hash disagrees with metadata");
			if (sourceOperation.operation() == OperationType.DELETE && sourceOperation.expectedExistingHash() != null
					&& !sourceOperation.expectedExistingHash().equalsIgnoreCase(conflict.sourceHash()))
				throw new IOException("Conflict deletion hash disagrees with metadata");
			boolean owned = activeLedger != null && activeLedger.entries().get(conflict.sourcePath()) != null
					&& activeLedger.entries().get(conflict.sourcePath()).historicalHashes().contains(new OwnershipLedger.Content(conflict.sourceHash().toLowerCase(Locale.ROOT), conflict.sourceSize()));
			if (conflict.action() == ConflictAction.REMOVE_OWNED && !owned) throw new IOException("Conflict claims ownership without ledger proof");
			if (conflict.action() == ConflictAction.PRESERVE_LOCAL && owned) throw new IOException("Conflict preserves a ledger-owned file as local");
			if (conflict.action() == ConflictAction.PRESERVE_LOCAL && sourceOperation.expectedExistingHash() == null)
				throw new IOException("Preserved local conflict does not pin the source hash");
			if (conflict.action() == ConflictAction.PRESERVE_LOCAL && !sourceOperation.expectedExistingHash().equalsIgnoreCase(conflict.sourceHash()))
				throw new IOException("Preserved local conflict source hash is not pinned");
			ProjectedFile projected = finalState.get(sourceKey);
			if (projected == null) throw new IOException("Conflict source is missing from projected final state");
		}
	}

	private OwnershipLedger cleanupLedger() throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = context.storage().readActiveState();
		if (state == null) return null;
		GenerationRecord active = new ClientGenerationStore(context.storage()).read(state.generationId)
				.orElseThrow(() -> new IOException("Active client generation record is missing: " + state.generationId));
		return active.ownershipLedger();
	}

	private void validateInstall(Operation operation, ProjectedFile projected) throws IOException {
		validateHash(operation.expectedObjectHash(), "install SHA-1");
		if (operation.expectedExistingHash() != null) validateHash(operation.expectedExistingHash(), "install expected SHA-1");
		if (operation.expectedSize() < 0 || (projected != null && (!projected.present() || operation.expectedSize() != projected.expectedSize()
				|| !operation.expectedObjectHash().equalsIgnoreCase(projected.expectedHash()))))
			throw new IOException("Install operation does not match projected final state");
		Path source = context.storage().objectFile(operation.expectedObjectHash()).normalize();
		if (!source.startsWith(context.storage().objectsDirectory()) || !FileIntegrity.matches(source, operation.expectedSize(), operation.expectedObjectHash()))
			throw new IOException("Required CAS object is missing or corrupt: " + operation.expectedObjectHash());
	}

	private static void validateDelete(Operation operation, ProjectedFile projected) throws IOException {
		if (operation.expectedObjectHash() != null || operation.expectedSize() != -1 || (projected != null && projected.present()))
			throw new IOException("Invalid delete operation metadata");
		if (operation.expectedExistingHash() != null) validateHash(operation.expectedExistingHash(), "deletion expected SHA-1");
	}

	private void validateManifestProjection(ModpackJsons.ModpackContentFields manifest, Map<FileKey, ProjectedFile> finalState) throws IOException {
		for (var item : manifest.list) {
			String relative = normalizeManifestPath(item.file);
			ProjectedFile projected = finalState.get(new FileKey(Root.PROJECTION, relative));
			if (projected == null || !projected.present()) throw new IOException("Manifest file is absent from projected final state: " + relative);
			if (!item.sha1.equalsIgnoreCase(projected.expectedHash()) || parseNonnegativeSize(item.size) != projected.expectedSize())
				throw new IOException("Manifest file does not match projected final state: " + relative);
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
				if (!publicationStarted && configurationChangedAfterPlanning(transaction))
					throw new UpdateReplanRequiredException(null, "Client configuration changed after planning the update");
				captureBaselines(transaction);
				preserveConflicts(transaction);
				preserveBeforeMutation(transaction);
				if (!liveAlreadyApplied) applyOperations(transaction, current);
				current.set(null);
				if (!publicationStarted) {
					verifyManagedFinalState(transaction);
					if (selectionChangedAfterPlanning(transaction)) throw new UpdateReplanRequiredException(null, "Group selection changed while applying the update");
					if (configurationChangedAfterPlanning(transaction))
						throw new UpdateReplanRequiredException(null, "Client configuration changed while applying the update");
				}
				if (transaction.operations.isEmpty()) {
					if (!verifyProjectionQuietly(context.storage().activeDirectory(), transaction.projectedFinalState)) {
						buildIncomingProjection(transaction);
						setPhase(transaction, UpdateTransaction.Phase.PROJECTED);
						setPhase(transaction, UpdateTransaction.Phase.SWAPPING);
						swapProjection(transaction);
					}
				} else {
					buildIncomingProjection(transaction);
					setPhase(transaction, UpdateTransaction.Phase.PROJECTED);
					setPhase(transaction, UpdateTransaction.Phase.SWAPPING);
					swapProjection(transaction);
				}
				if (publicationStarted && isModpackTransaction(transaction)
						&& (!managedStateMatches(transaction) || preserveNewerSelection || configurationChangedAfterPlanning(transaction)))
					throw new UpdateReplanRequiredException(null, "Mutable client state changed while publishing the update");
				if (selectionChangedAfterPlanning(transaction) || configurationChangedAfterPlanning(transaction))
					throw new UpdateReplanRequiredException(null, "Mutable client configuration changed before update finalization");
				ModpackJsons.ModpackContentFields target = resolvedTarget(transaction, storedRecord(transaction)).flatTarget();
				if (transaction.plannedClientConfig != null && !preserveNewerSelection)
					ConfigTools.writeAtomic(context.storage().clientConfigFile(), transaction.plannedClientConfig);
				if (context.beforeManifestAction() != null && transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE)
					context.beforeManifestAction().run(transaction, target);
				if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
					GenerationTarget generation = transaction.generationTarget();
					GeneratedCopyState.fromFields(transaction.plannedGeneratedCopies).write(context.storage());
					context.storage().writeActiveState(transaction.modpackId, generation.targetGenerationId());
				} else if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
					FileTrees.delete(context.storage().generatedCopiesGenerationDirectory(transaction.modpackId, transaction.targetGenerationId));
					context.storage().clearActiveState();
					Files.deleteIfExists(context.storage().baselineFile(transaction.modpackId));
				} else {
					context.storage().clearActiveState();
				}
				claimSelection(transaction);
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
			if (e instanceof UpdateReplanRequiredException replan) {
				if (blockedPath == null) blockedPath = replan.changedPath();
				transaction.phase = UpdateTransaction.Phase.DEFERRED;
				transaction.resultStatus = UpdateTransaction.Status.REPLAN_REQUIRED;
				transaction.resultOperation = currentOperation == null ? null : currentOperation.operation().name();
				transaction.resultPath = blockedPath == null ? null : blockedPath.toString();
				transaction.resultMessage = e.getMessage();
				try {
					ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
				} catch (IOException journalFailure) {
					e.addSuppressed(journalFailure);
				}
				return new Execution(UpdateTransaction.Status.REPLAN_REQUIRED, transaction, currentOperation == null ? null : currentOperation.operation().name(), blockedPath, e.getMessage());
			}
			if (isLockFailure(e)) {
				transaction.phase = UpdateTransaction.Phase.DEFERRED;
				transaction.resultStatus = UpdateTransaction.Status.DEFERRED_LOCKED;
				transaction.resultOperation = currentOperation == null ? null : currentOperation.operation().name();
				transaction.resultPath = blockedPath == null ? null : blockedPath.toString();
				transaction.resultMessage = e.getMessage();
				try {
					ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
				} catch (IOException journalFailure) {
					e.addSuppressed(journalFailure);
				}
				return new Execution(UpdateTransaction.Status.DEFERRED_LOCKED, transaction, currentOperation == null ? null : currentOperation.operation().name(), blockedPath, e.getMessage());
			}
			transaction.resultStatus = UpdateTransaction.Status.FAILED;
			transaction.resultOperation = currentOperation == null ? null : currentOperation.operation().name();
			transaction.resultPath = blockedPath == null ? null : blockedPath.toString();
			transaction.resultMessage = e.getMessage();
			try {
				ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
			} catch (IOException journalFailure) {
				e.addSuppressed(journalFailure);
			}
			throw new UpdateExecutionException(currentOperation == null ? null : currentOperation.operation().name(), blockedPath, e);
		}
	}

	private boolean isModpackTransaction(UpdateTransaction transaction) {
		return isModpackPurpose(transaction.purpose);
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
			if (FileIntegrity.matches(target, operation.expectedSize(), operation.expectedObjectHash())) continue;
			verifyExpectedExisting(operation, target);
			Path source = context.storage().objectFile(operation.expectedObjectHash());
			VerifiedFileTransfer.copyAtomic(source, target, operation.expectedSize(), operation.expectedObjectHash());
		}
		for (Operation operation : transaction.operations) {
			if (operation.operation() != OperationType.DELETE || operation.root() == Root.PROJECTION) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				verifyExpectedExisting(operation, target);
				Files.delete(target);
			}
			FileTrees.pruneEmptyAncestors(target, root(operation.root(), transaction));
		}
	}

	private void verifyExpectedExisting(Operation operation, Path target) throws IOException {
		if (operation.root() == Root.OVERLAY) {
			if (operation.expectedExistingHash() == null) {
				if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new UpdateReplanRequiredException(target, "Client overlay target appeared after planning: " + target);
			} else
				if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
						|| !FileIntegrity.matches(target, Files.size(target), operation.expectedExistingHash()))
					throw new UpdateReplanRequiredException(target, "Client overlay target changed after planning: " + target);
			return;
		}
		if (operation.root() != Root.GAME_DIR) return;
		if (operation.expectedExistingHash() == null) {
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new UpdateReplanRequiredException(target, "Game-directory target appeared after planning: " + target);
			return;
		}
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
				|| !FileIntegrity.matches(target, Files.size(target), operation.expectedExistingHash()))
			throw new UpdateReplanRequiredException(target, "Game-directory target changed after planning: " + target);
	}

	private void preserveBeforeMutation(UpdateTransaction transaction) throws IOException {
		for (Preservation preservation : transaction.plannedPreservations) {
			PreservationOrigin origin = preservationOrigin(transaction, preservation);
			PreservationVault.preserve(context.storage(), origin.modpackId(), origin.generationId(), origin.reason(), preservation.root(),
					preservation.relativePath(), preservation.expectedHash().toLowerCase(Locale.ROOT), preservation.expectedSize());
		}
	}

	private void preserveConflicts(UpdateTransaction transaction) throws IOException {
		for (Conflict conflict : transaction.plannedConflicts)
			if (conflict.action() == ConflictAction.PRESERVE_LOCAL) PreservationVault.preserveConflict(context.storage(), transaction.targetGenerationId, conflict);
	}

	private PreservationOrigin preservationOrigin(UpdateTransaction transaction, Preservation preservation) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields active = context.storage().readActiveState();
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL)
			return new PreservationOrigin(transaction.modpackId, active == null ? transaction.targetGenerationId : HashUtils.normalizeSha1(active.generationId), PreservationVault.Reason.MODPACK_REMOVAL);
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_DEACTIVATION)
			return new PreservationOrigin(transaction.modpackId, active == null ? transaction.targetGenerationId : HashUtils.normalizeSha1(active.generationId), PreservationVault.Reason.MODPACK_DEACTIVATION);
		if (preservation.proof() == PreservationProof.ACTIVE_LEDGER && active != null) {
			PreservationVault.Reason reason = transaction.modpackId.equals(active.modpackId) ? PreservationVault.Reason.SERVER_REMOVAL : PreservationVault.Reason.MODPACK_DEACTIVATION;
			return new PreservationOrigin(active.modpackId, HashUtils.normalizeSha1(active.generationId), reason);
		}
		if (preservation.proof() == PreservationProof.PLAYER_CONSENT)
			return new PreservationOrigin(transaction.modpackId, transaction.targetGenerationId, PreservationVault.Reason.PLAYER_CONSENT);
		return new PreservationOrigin(transaction.modpackId, transaction.targetGenerationId, PreservationVault.Reason.SERVER_REMOVAL);
	}

	private record PreservationOrigin(String modpackId, String generationId, PreservationVault.Reason reason) {}

	private void verifyManagedFinalState(UpdateTransaction transaction) throws IOException {
		for (ProjectedFile projected : transaction.projectedFinalState) {
			if (projected.root() == Root.PROJECTION) continue;
			Path target = resolve(projected.root(), projected.relativePath(), transaction);
			if (projected.present()) {
				if (!FileIntegrity.matches(target, projected.expectedSize(), projected.expectedHash()))
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
			expected.put(normalizeOperationPath(projected.relativePath()), projected.present()
					? new UpdatePlan.FileState(projected.expectedHash(), projected.expectedSize(), true)
					: new UpdatePlan.FileState(null, -1, false));
		}
		return expected.equals(ClientOverlaySnapshot.capture(context.storage(), transaction.modpackId, null).files());
	}

	private void buildIncomingProjection(UpdateTransaction transaction) throws IOException {
		Path incoming = context.storage().incomingProjectionDirectory();
		FileTrees.delete(incoming);
		Files.createDirectories(incoming);
		for (ProjectedFile projected : transaction.projectedFinalState) {
			if (projected.root() != Root.PROJECTION || !projected.present()) continue;
			Path source = context.storage().objectFile(projected.expectedHash());
			Path target = incoming.resolve(normalizeOperationPath(projected.relativePath())).normalize();
			if (!target.startsWith(incoming)) throw new IOException("Projection path escapes incoming directory");
			VerifiedFileTransfer.linkAtomic(source, target, projected.expectedSize(), projected.expectedHash());
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
		for (ProjectedFile projected : finalState) if (projected.root() == Root.PROJECTION && projected.present()) expected.put(normalizeOperationPath(projected.relativePath()), projected);
		if (!Files.isDirectory(projection, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Active client projection is not a directory: " + projection);
		try (var paths = Files.walk(projection)) {
			for (Path path : paths.filter(candidate -> !candidate.equals(projection)).toList()) {
				if (Files.isSymbolicLink(path)) throw new IOException("Client projection contains a symbolic link: " + path);
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
				String relative = normalizeOperationPath(projection.relativize(path).toString());
				ProjectedFile expectedFile = expected.remove(relative);
				if (expectedFile == null || !FileIntegrity.matches(path, expectedFile.expectedSize(), expectedFile.expectedHash()))
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
			entry.baselineGenerationId = transaction.parentGenerationId == null ? "" : transaction.parentGenerationId;
			if (capture.absent()) {
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Baseline path was expected to be absent: " + source);
				entry.absent = true;
				entry.objectHash = "";
				entry.size = -1;
			} else {
				if (!FileIntegrity.matches(source, capture.expectedSize(), capture.expectedHash())) throw new IOException("Baseline source changed: " + source);
				Path object = context.storage().objectFile(capture.expectedHash());
				VerifiedFileTransfer.copyAtomicImmutable(source, object, capture.expectedSize(), capture.expectedHash());
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
		SelectionIntent current = new ClientSelectionStore(context.storage().selectionFile()).get(transaction.modpackId).orElse(null);
		SelectionIntent expected = transaction.expectedPriorIntent();
		boolean alreadyCommitted = transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
				? Objects.equals(current, transaction.targetIntent())
				: transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL ? current == null : Objects.equals(current, expected);
		if (!Objects.equals(current, expected) && !alreadyCommitted) throw new IOException("Group selection changed after planning for modpack " + transaction.modpackId);
	}

	private Path resolve(Operation operation, UpdateTransaction transaction) throws IOException {
		return resolve(operation.root(), operation.relativePath(), transaction);
	}

	private Path resolve(Root root, String relativePath, UpdateTransaction transaction) throws IOException {
		Path base = root(root, transaction).toAbsolutePath().normalize();
		Path resolved = base.resolve(normalizeOperationPath(relativePath)).normalize();
		if (!resolved.startsWith(base)) throw new IOException("Operation escapes constrained root");
		validateNoSymbolicLinkDescendants(base, resolved);
		return resolved;
	}

	private Path root(Root root, UpdateTransaction transaction) throws IOException {
		return switch (root) {
			case PROJECTION -> context.storage().activeDirectory();
			case OVERLAY -> context.storage().overlayDirectory(transaction.modpackId);
			case GAME_DIR -> context.storage().gameDirectory();
		};
	}

	private Path validateRootAndPath(Root root, String relativePath, String currentModpackId, UpdateTransaction.Purpose purpose) throws IOException {
		UpdateTransaction synthetic = new UpdateTransaction();
		synthetic.modpackId = currentModpackId;
		Path constrainedRoot = root(root, synthetic).toAbsolutePath().normalize();
		Path resolved = constrainedRoot.resolve(relativePath).normalize();
		if (!resolved.startsWith(constrainedRoot)) throw new IOException("Transaction path escapes constrained root");
		if (root != Root.GAME_DIR && Files.isSymbolicLink(constrainedRoot)) throw new IOException("Transaction root is a symbolic link");
		validateNoSymbolicLinkDescendants(constrainedRoot, resolved);
		Path game = context.storage().gameDirectory();
		Path automodpack = context.storage().automodpackDirectory();
		if (root == Root.GAME_DIR && resolved.startsWith(automodpack)) throw new IOException("GAME_DIR operation uses a narrower root");
		if (root == Root.OVERLAY && !isModpackPurpose(purpose)) throw new IOException("OVERLAY is restricted to modpack transactions");
		if (root == Root.PROJECTION && !isModpackPurpose(purpose)) throw new IOException("PROJECTION is restricted to modpack transactions");
		if (!resolved.startsWith(game)) throw new IOException("Transaction target escaped the game directory");
		return resolved;
	}

	private static boolean isModpackPurpose(UpdateTransaction.Purpose purpose) {
		return purpose == UpdateTransaction.Purpose.MODPACK_UPDATE || purpose == UpdateTransaction.Purpose.MODPACK_DEACTIVATION
				|| purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL;
	}

	public static void validateNoSymbolicLinkDescendants(Path constrainedRoot, Path target) throws IOException {
		Path root = constrainedRoot.toAbsolutePath().normalize();
		Path resolved = target.toAbsolutePath().normalize();
		if (!resolved.startsWith(root)) throw new IOException("Target escapes constrained root");
		Path current = root;
		for (Path component : root.relativize(resolved)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) throw new IOException("Symbolic-link component is not allowed beneath transaction root: " + current);
		}
	}

	private static String normalizeManifestPath(String path) throws IOException {
		try {
			return UpdatePlanner.normalize(path);
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsafe manifest path", e);
		}
	}

	private static String normalizeOperationPath(String relativePath) throws IOException {
		if (relativePath == null || relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.matches("^[A-Za-z]:[\\\\/].*"))
			throw new IOException("Operation path must be relative");
		try {
			String normalized = UpdatePlanner.normalize(relativePath);
			if (!normalized.equals(relativePath.replace('\\', '/'))) throw new IOException("Path is not normalized: " + relativePath);
			return normalized;
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsafe operation path", e);
		}
	}

	private static long parseNonnegativeSize(String value) throws IOException {
		try {
			long size = Long.parseLong(value);
			if (size < 0) throw new NumberFormatException("negative");
			return size;
		} catch (RuntimeException e) {
			throw new IOException("Invalid nonnegative file size", e);
		}
	}

	private static void validateHash(String hash, String description) throws IOException {
		if (!HashUtils.isSha1(hash)) throw new IOException("Invalid " + description);
	}

	private static int compareFileKeys(FileKey first, FileKey second) {
		int root = Integer.compare(first.root().ordinal(), second.root().ordinal());
		return root != 0 ? root : first.relativePath().compareTo(second.relativePath());
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
