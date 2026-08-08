package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectedTreeComposer;
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
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

/** Validates and applies the one journaled client operation plan. */
public final class UpdateTransactionExecutor {
	private static final int COPY_CONCURRENCY = 3;
	private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
	private static final Comparator<Operation> OPERATION_ORDER = Comparator.comparing((Operation operation) -> operation.operation().ordinal())
			.thenComparing(operation -> operation.root().ordinal()).thenComparing(Operation::relativePath);
	private final Context context;

	@FunctionalInterface
	public interface CommitAction {
		void run(UpdateTransaction transaction, Jsons.ModpackContentFields target) throws IOException;
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
	}

	public UpdateTransactionExecutor(Context context) {
		this.context = Objects.requireNonNull(context);
	}

	public Execution commit(UpdatePlan plan, SelectedModpackTarget target) throws IOException {
		return commit(plan, target, context.storage().overlayDigest(plan.modpackId()));
	}

	public Execution commit(UpdatePlan plan, SelectedModpackTarget target, String overlayDigest) throws IOException {
		ClientStorage storage = context.storage();
		ensureNoActiveTransaction(storage);
		UpdateTransaction transaction = UpdateTransaction.create(plan, target, overlayDigest);
		new ClientGenerationStore(storage).write(target.generationRecord(), target.patchNotesHistory());
		return commit(transaction);
	}

	public Execution commit(UpdateTransaction transaction) throws IOException {
		validate(transaction);
		validateSelectionBeforeMutation(transaction);
		ensureNoActiveTransaction(context.storage());
		context.storage().ensureRoots();
		ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
		return executePersisted(transaction);
	}

	private static void ensureNoActiveTransaction(ClientStorage storage) throws IOException {
		if (Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("An update transaction is already active for this game directory");
	}

	public Execution recover(UpdateTransaction transaction) throws IOException {
		validate(transaction);
		validateSelectionBeforeMutation(transaction);
		return executePersisted(transaction);
	}

	public UpdateTransaction readPersisted() {
		return ConfigTools.read(context.storage().transactionFile(), UpdateTransaction.class).orElse(null);
	}

	public void validate(UpdateTransaction transaction) throws IOException {
		try {
			validateUnchecked(transaction);
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Invalid update transaction", e);
		}
	}

	private void validateUnchecked(UpdateTransaction transaction) throws IOException {
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

		Jsons.ModpackContentFields target = null;
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE || transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
			ModpackId.requireValid(transaction.modpackId);
			GenerationRecord record = storedRecord(transaction);
			target = resolvedTarget(transaction, record).flatTarget();
			validateGenerationIdentity(transaction, record, target);
			validateManifest(target, transaction.modpackId);
			validateSelectionMetadata(transaction);
			validateStoredClientState(transaction, record);
			if (transaction.plannedClientConfig == null) throw new IOException("Planned client config is missing");
			if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) validatePlannedClientConfig(transaction);
			else validateRemovalClientConfig(transaction);
			if (!Objects.equals(transaction.overlayDigest, context.storage().overlayDigest(transaction.modpackId)))
				throw new IOException("Client editable overlay changed after planning");
		} else if (transaction.purpose == UpdateTransaction.Purpose.SELF_UPDATE) validateSelfUpdateMetadata(transaction);
		else throw new IOException("Unsupported transaction purpose");

		Map<FileKey, ProjectedFile> finalState = validateFinalState(transaction.projectedFinalState, transaction.modpackId, transaction.purpose);
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
				case CREATE_DIRECTORY, REMOVE_EMPTY_DIRECTORY -> validateDirectoryOperation(operation);
			}
		}
		validateBaselineCaptures(transaction);
		validateConflicts(transaction, finalState, target);
		validatePreservations(transaction, finalState, target);
		if (transaction.purpose == UpdateTransaction.Purpose.SELF_UPDATE && !operationKeys.equals(finalState.keySet()))
			throw new IOException("Special-purpose transaction operations and projected final state must match exactly");
		if (target != null && transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) validateManifestProjection(target, finalState);
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
			Jsons.CompleteModpackContentFields fields = new ClientGenerationStore(context.storage()).readFields(transaction.targetGenerationId)
					.orElseThrow(() -> new IOException("Client generation record is missing: " + transaction.targetGenerationId));
			if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && expected == null)
				return SelectedModpackTarget.prepareDefault(fields, transaction.platform());
			return SelectedModpackTarget.prepare(fields, expected, transaction.targetIntent(), transaction.platform());
		} catch (RuntimeException e) {
			throw new IOException("Client generation selection is invalid", e);
		}
	}

	private void validateGenerationIdentity(UpdateTransaction transaction, GenerationRecord record, Jsons.ModpackContentFields target) throws IOException {
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
		Jsons.ClientGenerationStateFields state = context.storage().readActiveState();
		if (state == null) return;
		if (!ModpackId.isValid(state.modpackId)) throw new IOException("Active client state modpack ID is invalid");
		if (!SHA1.matcher(state.generationId).matches())
			throw new IOException("Active client state identity is invalid");
		GenerationRecord stateRecord = new ClientGenerationStore(context.storage()).read(state.generationId)
				.orElseThrow(() -> new IOException("Active client generation record is missing: " + state.generationId));
		if (!state.modpackId.equals(stateRecord.manifest().modpackId())) throw new IOException("Active client state and record belong to different modpacks");
		if (state.modpackId.equals(transaction.modpackId) && state.generationId.equals(targetRecord.metadata().generationId())) {
			if (!stateRecord.equals(targetRecord)) throw new IOException("Active client state disagrees with its generation record");
		}
	}

	private void validateSelectionMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.targetPlatform == null || transaction.expectedPriorRequestedGroups == null || transaction.expectedPriorExcludedGroups == null
				|| transaction.requestedGroups == null || transaction.excludedGroups == null)
			throw new IOException("Selection metadata is incomplete");
		if (!isCanonicalIntentList(transaction.expectedPriorRequestedGroups)
				|| !isCanonicalIntentList(transaction.expectedPriorExcludedGroups)
				|| !isCanonicalIntentList(transaction.requestedGroups) || !isCanonicalIntentList(transaction.excludedGroups))
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
		Jsons.ClientConfigFieldsV3 config = transaction.plannedClientConfig;
		if (config == null || !transaction.modpackId.equals(config.selectedModpackId))
			throw new IOException("Planned client config does not select the transaction modpack");
	}

	private void validateRemovalClientConfig(UpdateTransaction transaction) throws IOException {
		Jsons.ClientConfigFieldsV3 config = transaction.plannedClientConfig;
		if (config == null || transaction.modpackId.equals(config.selectedModpackId))
			throw new IOException("Removal client config still selects the removed modpack");
	}

	private static void validateSelfUpdateMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.modpackId != null || transaction.targetGenerationId != null || transaction.parentGenerationId != null || transaction.stateDigest != null
				|| transaction.ledgerDigest != null || transaction.targetPlatform != null || transaction.selectionDigest != null || transaction.overlayDigest != null
				|| transaction.expectedPriorSelectionPresent || transaction.expectedPriorRequestedGroups != null
				|| transaction.expectedPriorExcludedGroups != null || transaction.requestedGroups != null || transaction.excludedGroups != null
				|| transaction.plannedClientConfig != null || !transaction.restartReasons.isEmpty() || !transaction.plannedPreservations.isEmpty()
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
			if (relative.getNameCount() != 2 || !relative.getName(0).toString().equalsIgnoreCase("mods")
					|| !relative.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
				throw new IOException("Self-update target must be a direct JAR child of the mods directory");
		} else if (purpose == UpdateTransaction.Purpose.MODPACK_UPDATE || purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
			if (operation.root() != Root.PROJECTION && operation.root() != Root.OVERLAY && operation.root() != Root.GAME_DIR)
				throw new IOException("Modpack operations are restricted to projection, overlays, and managed live files");
		} else throw new IOException("Unsupported transaction purpose");
	}

	private void validateManifest(Jsons.ModpackContentFields manifest, String modpackId) throws IOException {
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
				if (entry.root() != Root.GAME_DIR || selfUpdatePath.getNameCount() != 2 || !selfUpdatePath.getName(0).toString().equalsIgnoreCase("mods")
						|| !selfUpdatePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
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

	private void validatePreservations(UpdateTransaction transaction, Map<FileKey, ProjectedFile> finalState, Jsons.ModpackContentFields target) throws IOException {
		boolean removal = transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL;
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && !removal) {
			if (!transaction.plannedPreservations.isEmpty()) throw new IOException("Only modpack transactions can preserve deleted files");
			return;
		}
		if (target == null) throw new IOException("Preservation validation has no target");
		if (transaction.plannedPreservations.isEmpty()) return;
		OwnershipLedger activeLedger = cleanupLedger();
		OwnershipLedger targetLedger = OwnershipLedger.fromFields(target.ownershipLedger);
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
			if (projected == null || projected.present()) throw new IOException("Preservation target is not absent from projected final state");
		}
	}

	private void validateConflicts(UpdateTransaction transaction, Map<FileKey, ProjectedFile> finalState, Jsons.ModpackContentFields target) throws IOException {
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE) {
			if (!transaction.plannedConflicts.isEmpty()) throw new IOException("Only modpack updates may contain conflicts");
			return;
		}
		if (target == null) throw new IOException("Conflict validation has no target");
		List<Conflict> sorted = transaction.plannedConflicts.stream().sorted(Comparator.comparing(Conflict::conflictId)).toList();
		if (!transaction.plannedConflicts.equals(sorted)) throw new IOException("Conflicts are not deterministically ordered");
		OwnershipLedger activeLedger = cleanupLedger();
		QuarantineArchive.read(context.storage(), transaction.modpackId);
		Set<String> targetPaths = new HashSet<>();
		for (var item : target.list) targetPaths.add(normalizeManifestPath(item.file));
		Set<String> conflictIds = new HashSet<>();
		for (Conflict conflict : sorted) {
			if (conflict == null || conflict.action() == null || !transaction.modpackId.equals(conflict.modpackId()) || !conflictIds.add(conflict.conflictId()))
				throw new IOException("Conflict identity is invalid");
			if (!conflict.sourcePath().startsWith("mods/") || !conflict.targetPath().startsWith("mods/") || !targetPaths.contains(conflict.targetPath()))
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
			if (conflict.action() == ConflictAction.QUARANTINE && owned) throw new IOException("Conflict quarantines a ledger-owned file");
			if (conflict.action() == ConflictAction.QUARANTINE && sourceOperation.expectedExistingHash() == null)
				throw new IOException("Quarantine conflict does not pin the source hash");
			if (conflict.action() == ConflictAction.QUARANTINE && !sourceOperation.expectedExistingHash().equalsIgnoreCase(conflict.sourceHash()))
				throw new IOException("Quarantine conflict source hash is not pinned");
			ProjectedFile projected = finalState.get(sourceKey);
			if (projected == null) throw new IOException("Conflict source is missing from projected final state");
		}
	}

	private OwnershipLedger cleanupLedger() throws IOException {
		Jsons.ClientGenerationStateFields state = context.storage().readActiveState();
		if (state == null) return null;
		GenerationRecord active = new ClientGenerationStore(context.storage()).read(state.generationId)
				.orElseThrow(() -> new IOException("Active client generation record is missing: " + state.generationId));
		return active.ownershipLedger();
	}

	private void validateInstall(Operation operation, ProjectedFile projected) throws IOException {
		if (operation.root() == Root.STORE_DIR) throw new IOException("Transactions may not mutate the content-addressed store");
		validateHash(operation.expectedObjectHash(), "install SHA-1");
		if (operation.expectedExistingHash() != null) validateHash(operation.expectedExistingHash(), "install expected SHA-1");
		if (operation.expectedSize() < 0 || (projected != null && (!projected.present() || operation.expectedSize() != projected.expectedSize()
				|| !operation.expectedObjectHash().equalsIgnoreCase(projected.expectedHash()))))
			throw new IOException("Install operation does not match projected final state");
		Path source = context.storage().objectsDirectory().resolve(operation.expectedObjectHash()).normalize();
		if (!source.startsWith(context.storage().objectsDirectory()) || !SmartFileUtils.isValidFile(source, operation.expectedSize(), operation.expectedObjectHash()))
			throw new IOException("Required CAS object is missing or corrupt: " + operation.expectedObjectHash());
	}

	private static void validateDelete(Operation operation, ProjectedFile projected) throws IOException {
		if (operation.root() == Root.STORE_DIR || operation.expectedObjectHash() != null || operation.expectedSize() != -1 || (projected != null && projected.present()))
			throw new IOException("Invalid delete operation metadata");
		if (operation.expectedExistingHash() != null) validateHash(operation.expectedExistingHash(), "deletion expected SHA-1");
	}

	private static void validateDirectoryOperation(Operation operation) throws IOException {
		if (operation.root() == Root.STORE_DIR || operation.expectedObjectHash() != null || operation.expectedExistingHash() != null || operation.expectedSize() != -1)
			throw new IOException("Invalid directory operation metadata");
	}

	private void validateManifestProjection(Jsons.ModpackContentFields manifest, Map<FileKey, ProjectedFile> finalState) throws IOException {
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
			cleanupTransactionDirectories(transaction);
			Files.deleteIfExists(context.storage().transactionFile());
			return new Execution(UpdateTransaction.Status.SUCCESS, transaction, null, null, null);
		}
		AtomicReference<Operation> current = new AtomicReference<>();
		Path blockedPath = null;
		try {
			transaction.resultStatus = null;
			transaction.resultOperation = null;
			transaction.resultPath = null;
			transaction.resultMessage = null;
			setPhase(transaction, UpdateTransaction.Phase.PREPARING);
			if (isModpackTransaction(transaction)) {
				captureBaselines(transaction);
				quarantineConflicts(transaction);
				preserveBeforeMutation(transaction);
				applyOperations(transaction, current);
				current.set(null);
				verifyManagedFinalState(transaction);
				buildIncomingProjection(transaction);
				setPhase(transaction, UpdateTransaction.Phase.PROJECTED);
				setPhase(transaction, UpdateTransaction.Phase.SWAPPING);
				swapProjection(transaction);
				Jsons.ModpackContentFields target = resolvedTarget(transaction, storedRecord(transaction)).flatTarget();
				if (transaction.plannedClientConfig != null) ConfigTools.writeAtomic(context.storage().clientConfigFile(), transaction.plannedClientConfig);
				if (context.beforeManifestAction() != null && transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE)
					context.beforeManifestAction().run(transaction, target);
				if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
					GenerationTarget generation = transaction.generationTarget();
					context.storage().writeActiveState(transaction.modpackId, generation.targetGenerationId());
				} else {
					context.storage().clearActiveState();
					Files.deleteIfExists(context.storage().baselineFile(transaction.modpackId));
				}
				claimSelection(transaction);
			} else applyOperations(transaction, current);
			setPhase(transaction, UpdateTransaction.Phase.COMMITTED);
			cleanupTransactionDirectories(transaction);
			Files.deleteIfExists(context.storage().transactionFile());
			return new Execution(UpdateTransaction.Status.SUCCESS, transaction, null, null, null);
		} catch (IOException e) {
			Operation currentOperation = current.get();
			if (blockedPath == null && currentOperation != null) blockedPath = resolve(currentOperation, transaction);
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
		return transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE || transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL;
	}

	private void setPhase(UpdateTransaction transaction, UpdateTransaction.Phase phase) throws IOException {
		transaction.phase = phase;
		ConfigTools.writeAtomic(context.storage().transactionFile(), transaction);
	}

	private void applyOperations(UpdateTransaction transaction, AtomicReference<Operation> current) throws IOException {
		for (Operation operation : transaction.operations) {
			if (operation.operation() != OperationType.CREATE_DIRECTORY) continue;
			current.set(operation);
			Files.createDirectories(resolve(operation, transaction));
		}
		for (Operation operation : transaction.operations) {
			if (operation.operation() != OperationType.INSTALL_OBJECT || operation.root() == Root.PROJECTION) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (SmartFileUtils.isValidFile(target, operation.expectedSize(), operation.expectedObjectHash())) continue;
			if (operation.expectedExistingHash() != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				long size = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? Files.size(target) : -1;
				if (!SmartFileUtils.isValidFile(target, size, operation.expectedExistingHash())) throw new IOException("Restore target changed after planning: " + target);
			}
			Path source = context.storage().objectsDirectory().resolve(operation.expectedObjectHash());
			SmartFileUtils.copyVerifiedAtomic(source, target, operation.expectedSize(), operation.expectedObjectHash());
		}
		for (Operation operation : transaction.operations) {
			if (operation.operation() != OperationType.DELETE || operation.root() == Root.PROJECTION) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
			if (operation.expectedExistingHash() != null && !operation.expectedExistingHash().equalsIgnoreCase(HashUtils.getHash(target)))
				throw new IOException("Deletion target changed after planning: " + target);
			Files.delete(target);
		}
		for (Operation operation : transaction.operations) {
			if (operation.operation() != OperationType.REMOVE_EMPTY_DIRECTORY) continue;
			current.set(operation);
			Path target = resolve(operation, transaction);
			if (SmartFileUtils.isEmptyDirectory(target)) Files.deleteIfExists(target);
		}
	}

	private void preserveBeforeMutation(UpdateTransaction transaction) throws IOException {
		Path objects = context.storage().objectsDirectory().toAbsolutePath().normalize();
		for (Preservation preservation : transaction.plannedPreservations) {
			Path source = resolve(preservation.root(), preservation.relativePath(), transaction);
			Path object = objects.resolve(preservation.expectedHash().toLowerCase(Locale.ROOT)).normalize();
			validateNoSymbolicLinkDescendants(objects, object);
			if (!SmartFileUtils.isValidFile(object, preservation.expectedSize(), preservation.expectedHash())) {
				if (!SmartFileUtils.isValidFile(source, preservation.expectedSize(), preservation.expectedHash()))
					throw new IOException("Preservation source changed after planning: " + source);
				SmartFileUtils.copyVerifiedAtomic(source, object, preservation.expectedSize(), preservation.expectedHash());
			}
			if (!SmartFileUtils.isValidFile(object, preservation.expectedSize(), preservation.expectedHash()))
				throw new IOException("Preserved object verification failed: " + object);
		}
	}

	private void quarantineConflicts(UpdateTransaction transaction) throws IOException {
		for (Conflict conflict : transaction.plannedConflicts)
			if (conflict.action() == ConflictAction.QUARANTINE) QuarantineArchive.archive(context.storage(), transaction.targetGenerationId, conflict);
	}

	private void verifyManagedFinalState(UpdateTransaction transaction) throws IOException {
		for (ProjectedFile projected : transaction.projectedFinalState) {
			if (projected.root() == Root.PROJECTION) continue;
			Path target = resolve(projected.root(), projected.relativePath(), transaction);
			if (projected.present()) {
				if (!SmartFileUtils.isValidFile(target, projected.expectedSize(), projected.expectedHash())) throw new IOException("Projected target verification failed: " + target);
			} else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Projected absent target exists: " + target);
		}
	}

	private void buildIncomingProjection(UpdateTransaction transaction) throws IOException {
		Path incoming = context.storage().incomingTransactionDirectory(transaction.transactionId);
		SmartFileUtils.deleteTree(incoming);
		Files.createDirectories(incoming);
		for (ProjectedFile projected : transaction.projectedFinalState) {
			if (projected.root() != Root.PROJECTION || !projected.present()) continue;
			Path source = context.storage().objectsDirectory().resolve(projected.expectedHash());
			Path target = incoming.resolve(normalizeOperationPath(projected.relativePath())).normalize();
			if (!target.startsWith(incoming)) throw new IOException("Projection path escapes incoming directory");
			SmartFileUtils.linkVerifiedAtomic(source, target, projected.expectedSize(), projected.expectedHash());
		}
		verifyProjection(incoming, transaction.projectedFinalState);
	}

	private void swapProjection(UpdateTransaction transaction) throws IOException {
		Path active = context.storage().activeDirectory();
		Path incoming = context.storage().incomingTransactionDirectory(transaction.transactionId);
		Path backup = context.storage().backupTransactionDirectory(transaction.transactionId);
		if (Files.exists(active, LinkOption.NOFOLLOW_LINKS) && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
			if (verifyProjectionQuietly(active, transaction.projectedFinalState)) {
				SmartFileUtils.deleteTree(incoming);
				SmartFileUtils.deleteTree(backup);
				return;
			}
			throw new IOException("Client projection swap has two non-final directories");
		}
		if (!Files.exists(incoming, LinkOption.NOFOLLOW_LINKS)) buildIncomingProjection(transaction);
		if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) SmartFileUtils.moveDirectoryAtomic(active, backup);
		try {
			SmartFileUtils.moveDirectoryAtomic(incoming, active);
		} catch (IOException e) {
			if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS) && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
				try {
					SmartFileUtils.moveDirectoryAtomic(backup, active);
				} catch (IOException restoreFailure) {
					e.addSuppressed(restoreFailure);
				}
			}
			throw e;
		}
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
				if (expectedFile == null || !SmartFileUtils.isValidFile(path, expectedFile.expectedSize(), expectedFile.expectedHash()))
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
		SmartFileUtils.deleteTree(context.storage().incomingTransactionDirectory(transaction.transactionId));
		SmartFileUtils.deleteTree(context.storage().backupTransactionDirectory(transaction.transactionId));
	}

	private void captureBaselines(UpdateTransaction transaction) throws IOException {
		if (transaction.plannedBaselineCaptures.isEmpty()) return;
		Path baselinePath = context.storage().baselineFile(transaction.modpackId);
		Jsons.ClientBaselineFields baseline = readBaseline(baselinePath, transaction.modpackId);
		Map<String, Jsons.ClientBaselineFields.EntryFields> entries = new TreeMap<>();
		for (Jsons.ClientBaselineFields.EntryFields entry : baseline.entries) entries.put(entry.logicalPath, entry);
		boolean changed = false;
		for (BaselineCapture capture : transaction.plannedBaselineCaptures) {
			String logicalPath = capture.relativePath();
			if (entries.containsKey(logicalPath)) continue;
			Path source = resolve(capture.root(), capture.relativePath(), transaction);
			Jsons.ClientBaselineFields.EntryFields entry = new Jsons.ClientBaselineFields.EntryFields();
			entry.logicalPath = logicalPath;
			entry.baselineGenerationId = transaction.parentGenerationId == null ? "" : transaction.parentGenerationId;
			if (capture.absent()) {
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Baseline path was expected to be absent: " + source);
				entry.absent = true;
				entry.objectHash = "";
				entry.size = -1;
			} else {
				if (!SmartFileUtils.isValidFile(source, capture.expectedSize(), capture.expectedHash())) throw new IOException("Baseline source changed: " + source);
				Path object = context.storage().objectsDirectory().resolve(capture.expectedHash());
				SmartFileUtils.copyVerifiedAtomic(source, object, capture.expectedSize(), capture.expectedHash());
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

	private Jsons.ClientBaselineFields readBaseline(Path path, String modpackId) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			Jsons.ClientBaselineFields empty = new Jsons.ClientBaselineFields();
			empty.modpackId = modpackId;
			return empty;
		}
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Baseline state is not a regular file");
		Jsons.ClientBaselineFields baseline = ConfigTools.read(path, Jsons.ClientBaselineFields.class).orElseThrow(() -> new IOException("Baseline state is empty"));
		if (baseline.schemaVersion != 1 || !modpackId.equals(baseline.modpackId) || baseline.entries == null) throw new IOException("Baseline state identity is invalid");
		return baseline;
	}

	private void claimSelection(UpdateTransaction transaction) throws IOException {
		if (!isModpackTransaction(transaction)) return;
		ClientSelectionStore selections = new ClientSelectionStore(context.storage().selectionFile());
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) selections.compareAndSet(transaction.modpackId, transaction.expectedPriorIntent(), transaction.targetIntent());
		else selections.remove(transaction.modpackId, transaction.expectedPriorIntent());
	}

	private void validateSelectionBeforeMutation(UpdateTransaction transaction) throws IOException {
		if (!isModpackTransaction(transaction)) return;
		SelectionIntent current = new ClientSelectionStore(context.storage().selectionFile()).get(transaction.modpackId).orElse(null);
		SelectionIntent expected = transaction.expectedPriorIntent();
		boolean alreadyCommitted = transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE ? Objects.equals(current, transaction.targetIntent()) : current == null;
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
			case STORE_DIR -> context.storage().objectsDirectory();
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
		if (root == Root.STORE_DIR) throw new IOException("STORE_DIR is read-only");
		if (root == Root.OVERLAY && !isModpackPurpose(purpose)) throw new IOException("OVERLAY is restricted to modpack transactions");
		if (root == Root.PROJECTION && !isModpackPurpose(purpose)) throw new IOException("PROJECTION is restricted to modpack transactions");
		if (!resolved.startsWith(game)) throw new IOException("Transaction target escaped the game directory");
		return resolved;
	}

	private static boolean isModpackPurpose(UpdateTransaction.Purpose purpose) {
		return purpose == UpdateTransaction.Purpose.MODPACK_UPDATE || purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL;
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
		if (hash == null || !SHA1.matcher(hash).matches()) throw new IOException("Invalid " + description);
	}

	private static int compareFileKeys(FileKey first, FileKey second) {
		int root = Integer.compare(first.root().ordinal(), second.root().ordinal());
		return root != 0 ? root : first.relativePath().compareTo(second.relativePath());
	}

	public static boolean isLockFailure(IOException exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof FileSystemException fileSystemException) {
				String detail = (Objects.toString(fileSystemException.getReason(), "") + " " + Objects.toString(fileSystemException.getMessage(), "")).toLowerCase(Locale.ROOT);
				if (detail.contains("used by another process") || detail.contains("being used by another process") || detail.contains("sharing violation")) return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
