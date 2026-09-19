package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.HconfConfigs;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
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
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** Every structural and precondition check a persisted transaction must pass before its plan may mutate live state. */
public final class UpdateTransactionValidator {
	private final ClientStorage storage;

	UpdateTransactionValidator(ClientStorage storage) {
		this.storage = Objects.requireNonNull(storage, "storage");
	}

	/** Throws when the transaction is not structurally valid; wraps undiagnosed failures with a common message. */
	void validate(UpdateTransaction transaction, SelectedModpackTarget selectedTarget, boolean verifyMutableInputs, FileCache fileCache) throws IOException {
		try {
			validateUnchecked(transaction, selectedTarget, verifyMutableInputs, fileCache);
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Invalid update transaction", e);
		}
	}

	void validatePendingReplacementEnvelope(UpdateTransaction pending) throws IOException {
		try {
			if (pending.schemaVersion != UpdateTransaction.CURRENT_SCHEMA_VERSION) throw new IOException("Unsupported deferred transaction schema");
			UUID.fromString(pending.transactionId);
			if (pending.purpose == null || pending.plan().projectedFinalState() == null)
				throw new IOException("Deferred transaction envelope is incomplete");
			ModpackId.requireValid(pending.plan().modpackId());
			validateFinalState(pending.plan().projectedFinalState(), pending.plan().modpackId());
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Deferred transaction envelope is invalid", e);
		}
	}

	void validateUnchecked(UpdateTransaction transaction, SelectedModpackTarget selectedTarget, boolean verifyMutableInputs, FileCache fileCache) throws IOException {
		if (transaction == null) throw new IOException("Transaction is missing");
		if (transaction.schemaVersion != UpdateTransaction.CURRENT_SCHEMA_VERSION) throw new IOException("Unsupported transaction schema");
		try {
			UUID.fromString(transaction.transactionId);
		} catch (RuntimeException e) {
			throw new IOException("Invalid transaction UUID", e);
		}
		if (transaction.purpose == null || transaction.phase == null) throw new IOException("Transaction purpose or phase is missing");
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && !transaction.plan().generatedCopies().isEmpty())
			throw new IOException("Generated-copy state is only valid for modpack update transactions");

		ModpackJsons.ModpackContentFields target = null;
		if (transaction.expectedClientConfig == null) throw new IOException("Expected client configuration is missing");
		ModpackId.requireValid(transaction.plan().modpackId());
		if (selectedTarget != null && transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE)
			throw new IOException("A supplied update target is only valid for a modpack update transaction");
		PackDocument record = selectedTarget == null ? targetDocument(transaction) : selectedTarget.document();
		target = selectedTarget == null ? resolvedTarget(transaction, record).flatTarget() : selectedTarget.flatTarget();
		if (selectedTarget != null) validateSelectedTargetMetadata(transaction, selectedTarget);
		validateGenerationIdentity(transaction, record, target);
		validateManifest(target, transaction.plan().modpackId());
		validateSelectionMetadata(transaction);
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) validateGeneratedCopies(transaction);
		validateStoredClientState(transaction, record);
		if (transaction.plan().plannedClientConfig() == null) throw new IOException("Planned client config is missing");
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) validatePlannedClientConfig(transaction);
		else validateRemovalClientConfig(transaction);
		if (verifyMutableInputs && mutableInputDrift(transaction).overlay())
			throw new IOException("Client editable overlay changed after planning");

		Map<FileKey, ProjectedFile> finalState = validateFinalState(transaction.plan().projectedFinalState(), transaction.plan().modpackId());
		for (Operation operation : transaction.plan().operations())
			if (operation == null || operation.root() == null || operation.operation() == null || operation.relativePath() == null)
				throw new IOException("Incomplete transaction operation");
		List<Operation> sortedOperations = transaction.plan().operations().stream().sorted(Operation.ORDER).toList();
		if (!transaction.plan().operations().equals(sortedOperations)) throw new IOException("Transaction operations are not deterministically ordered");
		Set<FileKey> operationKeys = new HashSet<>();
		Set<Path> operationTargets = new HashSet<>();
		for (Operation operation : transaction.plan().operations()) {
			String relative = normalizeOperationPath(operation.relativePath());
			FileKey key = new FileKey(operation.root(), relative);
			if (!operationKeys.add(key)) throw new IOException("Duplicate transaction operation target");
			Path physicalTarget = validateRootAndPath(operation.root(), relative, transaction.plan().modpackId());
			if (!operationTargets.add(physicalTarget)) throw new IOException("Transaction operations alias the same physical target");
			ProjectedFile projected = finalState.get(key);
			if (projected == null && operation.root() != Root.PROJECTION) throw new IOException("Operation target is missing from projected final state");
			switch (operation.operation()) {
				case INSTALL_OBJECT -> validateInstall(operation, projected, fileCache);
				case DELETE -> validateDelete(operation, projected);
			}
		}
		validateBaselineCaptures(transaction);
		validateConflicts(transaction, finalState, target);
		validatePreservations(transaction, finalState, target);
		if (target != null && transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) validateManifestProjection(target, finalState);
	}

	/** One mutable planning input's drift from the snapshot the transaction was planned against. */
	record MutableInputDrift(boolean configuration, boolean selection, boolean overlay) {
		boolean any() {
			return configuration || selection || overlay;
		}
	}

	/**
	 * The canonical mutable-input precondition: which planned-against inputs (client configuration, group selection,
	 * editable overlays) drifted since the transaction was planned. Evaluated once per stage boundary; the caller owns the throw.
	 */
	MutableInputDrift mutableInputDrift(UpdateTransaction transaction) throws IOException {
		return new MutableInputDrift(configurationChanged(transaction), selectionChanged(transaction),
				!Objects.equals(transaction.overlayDigest, storage.overlayDigest(transaction.plan().modpackId())));
	}

	private boolean configurationChanged(UpdateTransaction transaction) throws IOException {
		if (transaction.expectedClientConfig == null) return true;
		ClientConfigJsons.ClientConfigFieldsV3 current = currentClientConfig();
		if (current.equals(transaction.expectedClientConfig)) return false;
		return transaction.plan().plannedClientConfig() == null || !current.equals(transaction.plan().plannedClientConfig());
	}

	private ClientConfigJsons.ClientConfigFieldsV3 currentClientConfig() {
		return HconfConfigs.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class).orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
	}

	private boolean selectionChanged(UpdateTransaction transaction) throws IOException {
		SelectionIntent current = new ClientSelectionStore(storage.selectionFile()).get(transaction.plan().modpackId()).orElse(null);
		SelectionIntent expected = transaction.expectedPriorIntent();
		boolean alreadyCommitted = transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
				? Objects.equals(current, transaction.targetIntent())
				: transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL ? current == null : Objects.equals(current, expected);
		return !Objects.equals(current, expected) && !alreadyCommitted;
	}

	private static void validateSelectedTargetMetadata(UpdateTransaction transaction, SelectedModpackTarget target) throws IOException {
		if (!Objects.equals(transaction.expectedPriorIntent(), target.expectedPriorIntent()) || !transaction.targetIntent().equals(target.selection().intent())
				|| !Objects.equals(transaction.platform(), target.platform()))
			throw new IOException("Transaction selection metadata disagrees with the supplied target");
	}

	/** Validates the carried plan's generated copies: the identity is the plan's own, so only the entries' contract can be wrong. */
	private void validateGeneratedCopies(UpdateTransaction transaction) throws IOException {
		try {
			GeneratedCopyState.fromCopies(transaction.plan().modpackId(), transaction.plan().packTarget().contentToken(), transaction.selectionDigest(), transaction.plan().generatedCopies());
		} catch (RuntimeException e) {
			throw new IOException("Generated-copy state is invalid", e);
		}
	}

	PackDocument targetDocument(UpdateTransaction transaction) throws IOException {
		try {
			return new ClientGenerationStore(storage).document(transaction);
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Pending transaction target generation is invalid", e);
		}
	}

	SelectedModpackTarget resolvedTarget(UpdateTransaction transaction, PackDocument record) throws IOException {
		try {
			SelectionIntent expected = transaction.expectedPriorIntent();
			if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && expected == null) return SelectedModpackTarget.prepareDefault(record, transaction.platform());
			return SelectedModpackTarget.prepare(record, expected, transaction.targetIntent(), transaction.platform());
		} catch (RuntimeException e) {
			throw new IOException("Client generation selection is invalid", e);
		}
	}

	private void validateGenerationIdentity(UpdateTransaction transaction, PackDocument record, ModpackJsons.ModpackContentFields target) throws IOException {
		PackTarget transactionTarget;
		try {
			transactionTarget = transaction.packTarget();
		} catch (RuntimeException e) {
			throw new IOException("Transaction generation identity is invalid", e);
		}
		PackTarget recordTarget = PackTarget.from(record);
		PackTarget flatTarget = PackTarget.fromFlat(target);
		if (!transactionTarget.equals(recordTarget) || !transactionTarget.equals(flatTarget))
			throw new IOException("Transaction, target generation, and selected target identities disagree");
		if (!transaction.plan().modpackId().equals(record.manifest().modpackId())) throw new IOException("Target generation belongs to another modpack lineage");
		try {
			if (!OwnershipLedger.fromFields(target.ownershipLedger).equals(record.ownershipLedger())) throw new IOException("Selected target ledger disagrees with generation record");
		} catch (RuntimeException e) {
			throw new IOException("Selected target ledger is invalid", e);
		}
	}

	private void validateStoredClientState(UpdateTransaction transaction, PackDocument targetRecord) throws IOException {
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return;
		if (!ModpackId.isValid(state.modpackId)) throw new IOException("Active client state modpack ID is invalid");
		if (!HashUtils.isSha1(state.contentToken))
			throw new IOException("Active client state identity is invalid");
		PackDocument stateRecord = new ClientGenerationStore(storage).activeDocument()
				.orElseThrow(() -> new IOException("Active client generation is missing: " + state.contentToken));
		if (!state.modpackId.equals(stateRecord.manifest().modpackId())) throw new IOException("Active client state and generation belong to different modpacks");
		if (state.modpackId.equals(transaction.plan().modpackId()) && state.contentToken.equals(targetRecord.contentToken())) {
			if (!stateRecord.equals(targetRecord)) throw new IOException("Active client state disagrees with its generation record");
		}
	}

	private void validateSelectionMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.expectedPriorRequestedGroups == null || transaction.expectedPriorRequestedCategories == null
				|| transaction.expectedPriorExcludedGroups == null || transaction.requestedGroups == null || transaction.requestedCategories == null || transaction.excludedGroups == null)
			throw new IOException("Selection metadata is incomplete");
		if (!isCanonicalIntentList(transaction.expectedPriorRequestedGroups)
				|| !isCanonicalIntentList(transaction.expectedPriorRequestedCategories)
				|| !isCanonicalIntentList(transaction.expectedPriorExcludedGroups)
				|| !isCanonicalIntentList(transaction.requestedGroups) || !isCanonicalIntentList(transaction.requestedCategories)
				|| !isCanonicalIntentList(transaction.excludedGroups))
			throw new IOException("Selection metadata is not canonical");
		try {
			if (!transaction.selectionDigest().equals(UpdateTransaction.digest(transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
					? transaction.targetIntent()
					: transaction.expectedPriorIntent())))
				throw new IOException("Selection digest does not match selection metadata");
			if (transaction.targetPlatform != null && !ClientPlatform.parse(transaction.targetPlatform).id().equals(transaction.targetPlatform))
				throw new IOException("Selection platform is not canonical");
		} catch (RuntimeException e) {
			throw new IOException("Selection metadata is invalid", e);
		}
	}

	private void validatePlannedClientConfig(UpdateTransaction transaction) throws IOException {
		ClientConfigJsons.ClientConfigFieldsV3 config = transaction.plan().plannedClientConfig();
		if (config == null || !transaction.plan().modpackId().equals(config.selectedModpackId))
			throw new IOException("Planned client config does not select the transaction modpack");
	}

	private void validateRemovalClientConfig(UpdateTransaction transaction) throws IOException {
		ClientConfigJsons.ClientConfigFieldsV3 config = transaction.plan().plannedClientConfig();
		if (config == null || transaction.plan().modpackId().equals(config.selectedModpackId))
			throw new IOException("Removal client config still selects the removed modpack");
	}

	private void validateManifest(ModpackJsons.ModpackContentFields manifest, String modpackId) throws IOException {
		if (manifest == null || manifest.list == null || manifest.selectedGroups == null || !modpackId.equals(manifest.modpackId) || !ModpackId.isValid(manifest.modpackId))
			throw new IOException("Selected target identity is invalid");
		Set<String> normalizedPaths = new HashSet<>();
		for (var item : manifest.list) {
			if (item == null || item.type == null || item.type.isBlank()) throw new IOException("Manifest item is incomplete");
			String relative = normalizeManifestPath(item.file);
			if (!normalizedPaths.add(relative)) throw new IOException("Manifest contains duplicate normalized path: " + relative);
			if (item.size < 0) throw new IOException("Manifest file has a negative size: " + relative);
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

	Map<FileKey, ProjectedFile> validateFinalState(List<ProjectedFile> entries, String modpackId) throws IOException {
		Map<FileKey, ProjectedFile> finalState = new LinkedHashMap<>();
		Set<Path> physicalTargets = new HashSet<>();
		FileKey previous = null;
		for (ProjectedFile entry : entries) {
			if (entry == null || entry.root() == null) throw new IOException("Incomplete projected final-state entry");
			String relative = normalizeOperationPath(entry.relativePath());
			Path physicalTarget = validateRootAndPath(entry.root(), relative, modpackId);
			if (!physicalTargets.add(physicalTarget)) throw new IOException("Projected entries alias the same physical target");
			FileKey key = new FileKey(entry.root(), relative);
			if (previous != null && FileKey.ORDER.compare(previous, key) >= 0) throw new IOException("Projected final state is not uniquely ordered");
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
			if (!transaction.plan().baselineCaptures().isEmpty()) throw new IOException("Only modpack updates can capture baselines");
			return;
		}
		for (BaselineCapture capture : transaction.plan().baselineCaptures())
			if (capture == null || capture.root() == null || capture.relativePath() == null || capture.expectedHash() == null)
				throw new IOException("Invalid baseline capture");
		List<BaselineCapture> sorted = transaction.plan().baselineCaptures().stream().sorted(Comparator.comparing((BaselineCapture capture) -> capture.root().ordinal())
				.thenComparing(BaselineCapture::relativePath)).toList();
		if (!transaction.plan().baselineCaptures().equals(sorted)) throw new IOException("Baseline captures are not deterministically ordered");
		for (BaselineCapture capture : transaction.plan().baselineCaptures()) {
			if (capture == null || capture.root() != Root.GAME_DIR) throw new IOException("Invalid baseline capture");
			String relative = normalizeOperationPath(capture.relativePath());
			validateRootAndPath(capture.root(), relative, transaction.plan().modpackId());
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
			if (!transaction.plan().preservations().isEmpty()) throw new IOException("Only modpack transactions can preserve deleted files");
			return;
		}
		if (target == null) throw new IOException("Preservation validation has no target");
		if (transaction.plan().preservations().isEmpty()) return;
		for (Preservation preservation : transaction.plan().preservations())
			if (preservation == null || preservation.root() == null || preservation.relativePath() == null || preservation.expectedHash() == null || preservation.proof() == null)
				throw new IOException("Invalid preservation");
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		OwnershipLedger activeLedger = activeLedger(activeState);
		OwnershipLedger targetLedger = OwnershipLedger.fromFields(target.ownershipLedger);
		Set<String> targetPaths = new HashSet<>();
		for (var item : target.list) targetPaths.add(normalizeManifestPath(item.file));
		List<Preservation> sorted = transaction.plan().preservations().stream().sorted(Comparator.comparing((Preservation preservation) -> preservation.root().ordinal())
				.thenComparing(Preservation::relativePath).thenComparing(Preservation::expectedHash).thenComparingLong(Preservation::expectedSize)).toList();
		if (!transaction.plan().preservations().equals(sorted)) throw new IOException("Preservations are not deterministically ordered");
		Set<FileKey> preservationKeys = new HashSet<>();
		for (Preservation preservation : sorted) {
			if (preservation == null || preservation.root() != Root.GAME_DIR)
				throw new IOException("Invalid preservation root");
			if (preservation.proof() == null) throw new IOException("Preservation proof is missing");
			String relative = normalizeOperationPath(preservation.relativePath());
			validateRootAndPath(preservation.root(), relative, transaction.plan().modpackId());
			if (!preservationKeys.add(new FileKey(preservation.root(), relative))) throw new IOException("Duplicate preservation target");
			validateHash(preservation.expectedHash(), "preservation SHA-1");
			String logicalPath = relative;
			if (preservation.proof() == PreservationProof.PLAYER_CONSENT) {
				if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE
						|| Path.of(relative).getNameCount() != 2 || !Path.of(relative).getName(0).toString().equals(ModpackPathPolicy.MODS_ROOT))
					throw new IOException("Player-consent preservation is restricted to direct mods files on first install");
				Operation deletion = transaction.plan().operations().stream().filter(operation -> operation.root() == Root.GAME_DIR
						&& operation.operation() == OperationType.DELETE && relative.equals(operation.relativePath())).findFirst().orElse(null);
				Operation installation = transaction.plan().operations().stream().filter(operation -> operation.root() == Root.GAME_DIR
						&& operation.operation() == OperationType.INSTALL_OBJECT && relative.equals(operation.relativePath())).findFirst().orElse(null);
				boolean deleteProof = deletion != null && deletion.expectedExistingHash() != null && preservation.expectedHash().equalsIgnoreCase(deletion.expectedExistingHash());
				boolean replaceProof = installation != null && installation.expectedExistingHash() != null
						&& preservation.expectedHash().equalsIgnoreCase(installation.expectedExistingHash());
				if (!deleteProof && !replaceProof) throw new IOException("Player-consent preservation has no matching hash-pinned operation");
				if (activeState != null && !(activeState.modpackId.equals(transaction.plan().modpackId()) && activeState.contentToken.equals(transaction.plan().packTarget().contentToken())))
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

	private void validateConflicts(UpdateTransaction transaction, Map<FileKey, ProjectedFile> finalState, ModpackJsons.ModpackContentFields target) throws IOException {
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE) {
			if (!transaction.plan().conflicts().isEmpty()) throw new IOException("Only modpack updates may contain conflicts");
			return;
		}
		if (target == null) throw new IOException("Conflict validation has no target");
		for (Conflict conflict : transaction.plan().conflicts()) {
			if (conflict == null) throw new IOException("Conflict identity is invalid");
			try {
				conflict.validate();
			} catch (RuntimeException e) {
				throw new IOException("Conflict metadata is invalid", e);
			}
		}
		List<Conflict> sorted = transaction.plan().conflicts().stream().sorted(Comparator.comparing(Conflict::conflictId)).toList();
		if (!transaction.plan().conflicts().equals(sorted)) throw new IOException("Conflicts are not deterministically ordered");
		OwnershipLedger activeLedger = activeLedger(storage.readActiveState());
		Set<String> targetPaths = new HashSet<>();
		for (var item : target.list) targetPaths.add(normalizeManifestPath(item.file));
		Set<String> conflictIds = new HashSet<>();
		for (Conflict conflict : sorted) {
			if (conflict == null || conflict.action() == null || !transaction.plan().modpackId().equals(conflict.modpackId()) || !conflictIds.add(conflict.conflictId()))
				throw new IOException("Conflict identity is invalid");
			if (!ModpackPathPolicy.isModPath(conflict.sourcePath()) || !ModpackPathPolicy.isModPath(conflict.targetPath()) || !targetPaths.contains(conflict.targetPath()))
				throw new IOException("Conflict path is outside the selected target mods");
			validateHash(conflict.sourceHash(), "conflict source SHA-1");
			validateHash(conflict.targetHash(), "conflict target SHA-1");
			if (conflict.sourceSize() < 0 || conflict.targetSize() < 0 || conflict.modIds().isEmpty()) throw new IOException("Conflict content metadata is invalid");
			FileKey sourceKey = new FileKey(Root.GAME_DIR, conflict.sourcePath());
			Operation sourceOperation = null;
			for (Operation operation : transaction.plan().operations())
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

	/** The active client state's ownership ledger, or null when no pack is active. */
	private static OwnershipLedger activeLedger(ClientStorageJsons.ClientGenerationStateFields state) {
		if (state == null) return null;
		return OwnershipLedger.fromFields(state.ownershipLedger);
	}

	private void validateInstall(Operation operation, ProjectedFile projected, FileCache fileCache) throws IOException {
		validateHash(operation.expectedObjectHash(), "install SHA-1");
		if (operation.expectedExistingHash() != null) validateHash(operation.expectedExistingHash(), "install expected SHA-1");
		if (operation.expectedSize() < 0 || (projected != null && (!projected.present() || operation.expectedSize() != projected.expectedSize()
				|| !operation.expectedObjectHash().equalsIgnoreCase(projected.expectedHash()))))
			throw new IOException("Install operation does not match projected final state");
		Path source = storage.objectFile(operation.expectedObjectHash()).normalize();
		if (!source.startsWith(storage.objectsDirectory()) || !FileIntegrity.matchesNamed(source, operation.expectedSize(), operation.expectedObjectHash(), fileCache))
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
			if (!item.sha1.equalsIgnoreCase(projected.expectedHash()) || item.size != projected.expectedSize())
				throw new IOException("Manifest file does not match projected final state: " + relative);
		}
	}

	Path validateRootAndPath(Root root, String relativePath, String currentModpackId) throws IOException {
		Path constrainedRoot = storage.root(root, currentModpackId);
		Path resolved = FileTrees.resolveConfined(constrainedRoot, relativePath, "Transaction target");
		Path game = storage.gameDirectory();
		Path automodpack = storage.automodpackDirectory();
		if (root == Root.GAME_DIR && resolved.startsWith(automodpack)) throw new IOException("GAME_DIR operation uses a narrower root");
		if (!resolved.startsWith(game)) throw new IOException("Transaction target escaped the game directory");
		return resolved;
	}

	static String normalizeManifestPath(String path) throws IOException {
		try {
			return LogicalPath.normalize(path);
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsafe manifest path", e);
		}
	}

	static String normalizeOperationPath(String relativePath) throws IOException {
		if (relativePath == null || relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.matches("^[A-Za-z]:[\\\\/].*"))
			throw new IOException("Operation path must be relative");
		try {
			String normalized = LogicalPath.normalize(relativePath);
			if (!normalized.equals(relativePath.replace('\\', '/'))) throw new IOException("Path is not normalized: " + relativePath);
			return normalized;
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsafe operation path", e);
		}
	}

	static void validateHash(String hash, String description) throws IOException {
		if (!HashUtils.isSha1(hash)) throw new IOException("Invalid " + description);
	}
}
