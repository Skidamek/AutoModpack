package pl.skidam.automodpack_core.update;

import static pl.skidam.automodpack_core.Constants.clientSelectionFile;
import static pl.skidam.automodpack_core.Constants.modpackBaselineFileName;
import static pl.skidam.automodpack_core.Constants.modpackCatalogueFileName;
import static pl.skidam.automodpack_core.Constants.modpackContentFileName;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
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
import pl.skidam.automodpack_core.update.UpdatePlan.*;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.LegacyDummyFiles;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

public final class UpdateTransactionExecutor {
	private static final int COPY_CONCURRENCY = 3;
	private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
	private static final Comparator<Operation> OPERATION_ORDER = Comparator.comparing((Operation operation) -> operation.operation().ordinal())
			.thenComparing(operation -> operation.root().ordinal()).thenComparing(Operation::relativePath);
	private final Context context;

	@FunctionalInterface
	public interface CommitAction {
		void run(UpdateTransaction transaction) throws IOException;
	}

	public record Context(
			Path gameDirectory,
			Path modpackDirectory,
			Path modsDirectory,
			Path storeDirectory,
			Path automodpackDirectory,
			Path transactionFile,
			Path transactionResultFile,
			Path clientConfigFile,
			Path installedManifestFile,
			Path completeCatalogueFile,
			Path selectionFile,
			CommitAction beforeManifestAction) {}

	public record Execution(UpdateTransactionResult.Status status, UpdateTransaction transaction, String operation, Path blockedPath, String message) {
		public boolean success() {
			return status == UpdateTransactionResult.Status.SUCCESS;
		}
	}

	public UpdateTransactionExecutor(Context context) {
		this.context = Objects.requireNonNull(context);
	}

	public Execution commit(UpdatePlan plan, SelectedModpackTarget target) throws IOException {
		return commit(UpdateTransaction.create(plan, target, context.modpackDirectory()));
	}

	public Execution commit(UpdateTransaction transaction) throws IOException {
		validate(transaction);
		validateSelectionBeforeMutation(transaction);
		if (Files.exists(context.transactionFile())) throw new IOException("An update transaction is already active for this game directory");
		ConfigTools.writeAtomic(context.transactionFile(), transaction);
		Files.deleteIfExists(context.transactionResultFile());
		return executePersisted(transaction);
	}

	public Execution recover(UpdateTransaction transaction) throws IOException {
		validate(transaction);
		validateSelectionBeforeMutation(transaction);
		return executePersisted(transaction);
	}

	private void validateSelectionBeforeMutation(UpdateTransaction transaction) throws IOException {
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && transaction.purpose != UpdateTransaction.Purpose.MODPACK_REMOVAL) return;
		SelectionIntent current = new ClientSelectionStore(context.selectionFile()).get(transaction.modpackId).orElse(null);
		SelectionIntent expected = transaction.expectedPriorIntent();
		boolean alreadyCommitted = transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE
				? Objects.equals(current, transaction.targetIntent())
				: current == null;
		if (!Objects.equals(current, expected) && !alreadyCommitted)
			throw new IOException("Group selection changed after planning for modpack " + transaction.modpackId);
	}

	private void claimSelection(UpdateTransaction transaction) throws IOException {
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && transaction.purpose != UpdateTransaction.Purpose.MODPACK_REMOVAL) return;
		ClientSelectionStore selections = new ClientSelectionStore(context.selectionFile());
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE)
			selections.compareAndSet(transaction.modpackId, transaction.expectedPriorIntent(), transaction.targetIntent());
		else
			if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL)
				selections.remove(transaction.modpackId, transaction.expectedPriorIntent());
	}

	public UpdateTransaction readPersisted() {
		return ConfigTools.read(context.transactionFile(), UpdateTransaction.class).orElse(null);
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
		if (transaction.purpose == null) throw new IOException("Transaction purpose is missing");
		Path gameDirectory = context.gameDirectory().toAbsolutePath().normalize();
		Path automodpackDirectory = context.automodpackDirectory().toAbsolutePath().normalize();
		if (!context.modsDirectory().toAbsolutePath().normalize().equals(gameDirectory.resolve("mods"))
				|| !automodpackDirectory.equals(gameDirectory.resolve("automodpack"))
				|| !context.storeDirectory().toAbsolutePath().normalize().equals(automodpackDirectory.resolve("store"))
				|| !context.transactionFile().toAbsolutePath().normalize().equals(automodpackDirectory.resolve(".private/update-transaction.json"))
				|| !context.transactionResultFile().toAbsolutePath().normalize().equals(automodpackDirectory.resolve(".private/update-transaction-result.json")))
			throw new IOException("Transaction roots do not match the game-directory layout");
		if (transaction.operations == null || transaction.projectedFinalState == null || transaction.restartReasons == null
				|| transaction.plannedPreservations == null || transaction.plannedBaselineCaptures == null)
			throw new IOException("Transaction fields are incomplete");

		Jsons.ModpackContentFields manifest = null;
		switch (transaction.purpose) {
			case MODPACK_UPDATE -> {
				ModpackId.requireValid(transaction.modpackId);
				validateModpackIdentity(transaction);
				try {
					manifest = transaction.targetManifest();
				} catch (RuntimeException e) {
					throw new IOException("Invalid embedded target manifest", e);
				}
				validateManifest(manifest, transaction.modpackId);
				GenerationRecord completeRecord;
				try {
					completeRecord = transaction.completeGenerationRecord();
				} catch (RuntimeException e) {
					throw new IOException("Invalid embedded complete generation record", e);
				}
				validateGenerationIdentity(transaction, completeRecord, manifest);
				validateGroupTarget(transaction, completeRecord, manifest);
				validateStoredGenerationState(transaction, completeRecord);
				if (transaction.plannedClientConfig == null) throw new IOException("Planned client config is missing");
				validatePlannedClientConfig(transaction);
				validateOrderedMetadata(transaction);
			}
			case MODPACK_REMOVAL -> manifest = validateRemovalMetadata(transaction);
			case SELF_UPDATE -> validateSelfUpdateMetadata(transaction);
			case LEGACY_DUMMY_CLEANUP -> validateLegacyDummyCleanupMetadata(transaction);
		}

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
			if (projected == null) throw new IOException("Operation target is missing from projected final state");
			switch (operation.operation()) {
				case INSTALL_OBJECT -> validateInstall(operation, projected);
				case DELETE -> validateDelete(operation, projected);
				case CREATE_DIRECTORY, REMOVE_EMPTY_DIRECTORY -> validateDirectoryOperation(operation);
			}
		}
		validateBaselineCaptures(transaction);
		validatePreservations(transaction, finalState, manifest);
		if ((transaction.purpose == UpdateTransaction.Purpose.SELF_UPDATE || transaction.purpose == UpdateTransaction.Purpose.LEGACY_DUMMY_CLEANUP)
				&& !operationKeys.equals(finalState.keySet()))
			throw new IOException("Special-purpose transaction operations and projected final state must match exactly");
		if (manifest != null && transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) validateManifestProjection(manifest, finalState);
	}

	private void validateModpackIdentity(UpdateTransaction transaction) throws IOException {
		if (context.modpackDirectory() == null) throw new IOException("Modpack transaction context is incomplete");
		Path expectedModpackDirectory = context.modpackDirectory().toAbsolutePath().normalize();
		Path stableModpackDirectory = context.automodpackDirectory().resolve("modpacks").resolve(transaction.modpackId).toAbsolutePath().normalize();
		Path recordedModpackDirectory;
		try {
			recordedModpackDirectory = Path.of(transaction.canonicalModpackDirectory).toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			throw new IOException("Invalid canonical modpack directory", e);
		}
		if (!expectedModpackDirectory.equals(stableModpackDirectory) || !expectedModpackDirectory.equals(recordedModpackDirectory))
			throw new IOException("Transaction modpack directory is not stable modpack storage");
		if (context.completeCatalogueFile() == null || !context.completeCatalogueFile().toAbsolutePath().normalize()
				.equals(expectedModpackDirectory.resolve(modpackCatalogueFileName)))
			throw new IOException("Complete catalogue path does not match stable modpack storage");
		if (context.installedManifestFile() == null || !context.installedManifestFile().toAbsolutePath().normalize()
				.equals(expectedModpackDirectory.resolve(modpackContentFileName)))
			throw new IOException("Installed manifest path does not match stable modpack storage");
		if (context.selectionFile() == null || !context.selectionFile().toAbsolutePath().normalize()
				.equals(context.gameDirectory().resolve(clientSelectionFile).toAbsolutePath().normalize()))
			throw new IOException("Selection store path does not match AutoModpack storage");
	}

	private Jsons.ModpackContentFields validateRemovalMetadata(UpdateTransaction transaction) throws IOException {
		ModpackId.requireValid(transaction.modpackId);
		validateModpackIdentity(transaction);
		Jsons.ModpackContentFields manifest;
		GenerationRecord completeRecord;
		try {
			manifest = transaction.targetManifest();
			completeRecord = transaction.completeGenerationRecord();
		} catch (RuntimeException e) {
			throw new IOException("Removal catalogue metadata is invalid", e);
		}
		validateManifest(manifest, transaction.modpackId);
		validateGenerationIdentity(transaction, completeRecord, manifest);
		if (transaction.targetPlatform == null) throw new IOException("Removal platform is missing");
		try {
			transaction.platform();
		} catch (RuntimeException e) {
			throw new IOException("Removal platform is invalid", e);
		}
		if (transaction.expectedPriorRequestedTags == null || transaction.expectedPriorRequestedGroups == null || transaction.expectedPriorExcludedGroups == null
				|| (!transaction.expectedPriorSelectionPresent && (!transaction.expectedPriorRequestedTags.isEmpty() || !transaction.expectedPriorRequestedGroups.isEmpty()
						|| !transaction.expectedPriorExcludedGroups.isEmpty())))
			throw new IOException("Removal selection metadata is inconsistent");
		if (!isCanonicalIntentList(transaction.expectedPriorRequestedTags) || !isCanonicalIntentList(transaction.expectedPriorRequestedGroups)
				|| !isCanonicalIntentList(transaction.expectedPriorExcludedGroups) || transaction.requestedTags == null || !transaction.requestedTags.isEmpty()
				|| transaction.requestedGroups == null || !transaction.requestedGroups.isEmpty() || transaction.excludedGroups == null || !transaction.excludedGroups.isEmpty())
			throw new IOException("Removal selection metadata is invalid");
		if (transaction.plannedClientConfig == null) throw new IOException("Removal client config is missing");
		validateRemovalClientConfig(transaction);
		validateOrderedMetadata(transaction);
		GenerationRecord storedCatalogue = ModpackContentTools.readGenerationRecord(context.completeCatalogueFile());
		if (storedCatalogue != null && !storedCatalogue.equals(completeRecord)) throw new IOException("Stored complete catalogue does not match removal target");
		Jsons.ModpackContentFields storedManifest = ModpackContentTools.read(context.installedManifestFile());
		if (storedManifest != null && !flatManifestState(storedManifest).equals(flatManifestState(manifest)))
			throw new IOException("Stored selected manifest does not match removal target");
		return manifest;
	}

	private void validateRemovalClientConfig(UpdateTransaction transaction) throws IOException {
		Jsons.ClientConfigFieldsV3 config = transaction.plannedClientConfig;
		if (config.modpackConnections == null || transaction.modpackId.equals(config.selectedModpackId)
				|| config.modpackConnections.containsKey(transaction.modpackId))
			throw new IOException("Removal client config still selects the removed modpack");
	}

	private static void validateSelfUpdateMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.modpackId != null || transaction.targetGenerationId != null || transaction.parentGenerationId != null || transaction.stateDigest != null || transaction.ledgerDigest != null
				|| transaction.completeManifestJson != null || transaction.targetManifestJson != null || transaction.targetPlatform != null
				|| transaction.expectedPriorSelectionPresent || transaction.expectedPriorRequestedTags != null || transaction.expectedPriorRequestedGroups != null
				|| transaction.expectedPriorExcludedGroups != null || transaction.requestedTags != null || transaction.requestedGroups != null || transaction.excludedGroups != null
				|| transaction.canonicalModpackDirectory != null
				|| transaction.plannedClientConfig != null || !transaction.restartReasons.isEmpty() || !transaction.plannedPreservations.isEmpty() || !transaction.plannedBaselineCaptures.isEmpty())
			throw new IOException("Self-update transaction contains modpack metadata");
		long installs = transaction.operations.stream().filter(operation -> operation.operation() == OperationType.INSTALL_OBJECT).count();
		long deletions = transaction.operations.stream().filter(operation -> operation.operation() == OperationType.DELETE).count();
		if (installs != 1 || deletions > 1 || transaction.operations.size() != installs + deletions)
			throw new IOException("Self-update transaction must contain one install and at most one deletion");
	}

	private static void validateLegacyDummyCleanupMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.modpackId != null || transaction.targetGenerationId != null || transaction.parentGenerationId != null || transaction.stateDigest != null || transaction.ledgerDigest != null
				|| transaction.completeManifestJson != null || transaction.targetManifestJson != null || transaction.targetPlatform != null
				|| transaction.expectedPriorSelectionPresent || transaction.expectedPriorRequestedTags != null || transaction.expectedPriorRequestedGroups != null
				|| transaction.expectedPriorExcludedGroups != null || transaction.requestedTags != null || transaction.requestedGroups != null || transaction.excludedGroups != null
				|| transaction.canonicalModpackDirectory != null
				|| transaction.plannedClientConfig != null || !transaction.restartReasons.isEmpty() || !transaction.plannedPreservations.isEmpty() || !transaction.plannedBaselineCaptures.isEmpty())
			throw new IOException("Legacy dummy cleanup transaction contains modpack metadata");
		if (transaction.operations.isEmpty()) throw new IOException("Legacy dummy cleanup transaction has no targets");
	}

	private static void validateGenerationIdentity(UpdateTransaction transaction, GenerationRecord completeRecord,
			Jsons.ModpackContentFields manifest) throws IOException {
		GenerationTarget transactionTarget;
		try {
			transactionTarget = transaction.generationTarget();
		} catch (RuntimeException e) {
			throw new IOException("Transaction generation identity is invalid", e);
		}
		GenerationTarget recordTarget = GenerationTarget.from(completeRecord);
		GenerationTarget flatTarget;
		try {
			flatTarget = GenerationTarget.fromFlat(manifest);
		} catch (RuntimeException e) {
			throw new IOException("Selected target generation identity is invalid", e);
		}
		if (!transactionTarget.equals(recordTarget) || !transactionTarget.equals(flatTarget))
			throw new IOException("Transaction, complete catalogue, and selected target generation identities disagree");
		if (!transaction.modpackId.equals(completeRecord.manifest().modpackId()))
			throw new IOException("Complete catalogue modpack ID does not match transaction");
		try {
			OwnershipLedger targetLedger = OwnershipLedger.fromFields(manifest.ownershipLedger);
			if (!targetLedger.equals(completeRecord.ownershipLedger())) throw new IOException("Selected target ledger does not match complete catalogue");
		} catch (RuntimeException e) {
			throw new IOException("Selected target ledger is invalid", e);
		}
	}

	private static void validatePurposeOperation(UpdateTransaction.Purpose purpose, Operation operation) throws IOException {
		if (purpose == UpdateTransaction.Purpose.SELF_UPDATE) {
			if (operation.root() != Root.MODS_DIR || (operation.operation() != OperationType.INSTALL_OBJECT && operation.operation() != OperationType.DELETE))
				throw new IOException("Self-update operations are restricted to JAR replacement in the mods directory");
			Path relative = Path.of(operation.relativePath());
			if (relative.getNameCount() != 1 || !relative.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
				throw new IOException("Self-update target must be a direct JAR child of the mods directory");
		} else if (purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
			if ((operation.operation() != OperationType.INSTALL_OBJECT && operation.operation() != OperationType.DELETE)
					|| (operation.root() != Root.MODPACK_DIR && operation.root() != Root.GAME_DIR && operation.root() != Root.MODS_DIR))
				throw new IOException("Modpack removal operations are restricted to managed files");
		} else if (purpose == UpdateTransaction.Purpose.LEGACY_DUMMY_CLEANUP) {
			if (operation.operation() != OperationType.DELETE
					|| (operation.root() != Root.GAME_DIR && operation.root() != Root.MODS_DIR && operation.root() != Root.AUTOMODPACK_DIR)
					|| !LegacyDummyFiles.SHA1.equals(operation.expectedExistingHash()))
				throw new IOException("Legacy dummy cleanup operations are restricted to verified constrained deletions");
		}
	}

	private void validateManifest(Jsons.ModpackContentFields manifest, String modpackId) throws IOException {
		if (manifest == null || manifest.list == null || manifest.selectedGroups == null || !modpackId.equals(manifest.modpackId)
				|| !ModpackId.isValid(manifest.modpackId))
			throw new IOException("Embedded manifest identity is invalid");
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

	private void validateGroupTarget(UpdateTransaction transaction, GenerationRecord completeRecord, Jsons.ModpackContentFields manifest) throws IOException {
		if (transaction.completeManifestJson == null || transaction.targetPlatform == null || transaction.expectedPriorRequestedTags == null
				|| transaction.expectedPriorRequestedGroups == null || transaction.expectedPriorExcludedGroups == null || transaction.requestedTags == null
				|| transaction.requestedGroups == null || transaction.excludedGroups == null)
			throw new IOException("Group transaction metadata is incomplete");
		if (!isCanonicalIntentList(transaction.expectedPriorRequestedTags) || !isCanonicalIntentList(transaction.expectedPriorRequestedGroups)
				|| !isCanonicalIntentList(transaction.expectedPriorExcludedGroups) || !isCanonicalIntentList(transaction.requestedTags)
				|| !isCanonicalIntentList(transaction.requestedGroups) || !isCanonicalIntentList(transaction.excludedGroups))
			throw new IOException("Group selection intent is not uniquely ordered");
		try {
			ClientPlatform platform = transaction.platform();
			if (!platform.id().equals(transaction.targetPlatform)) throw new IOException("Transaction platform is not canonical");
			ResolvedSelection resolved = GroupSelectionResolver.resolve(completeRecord.manifest(), transaction.targetIntent(), platform);
			Jsons.ModpackContentFields recomposed = SelectedTreeComposer.compose(completeRecord.manifest(), resolved,
					GenerationTarget.from(completeRecord));
			recomposed.ownershipLedger = completeRecord.ownershipLedger().toFields();
			if (!flatManifestState(recomposed).equals(flatManifestState(manifest)))
				throw new IOException("Embedded selected manifest does not match the complete catalogue and selection intent");
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Invalid complete catalogue or group selection", e);
		}
	}

	private void validateStoredGenerationState(UpdateTransaction transaction, GenerationRecord completeRecord) throws IOException {
		GenerationTarget target = transaction.generationTarget();
		Path catalogue = context.completeCatalogueFile();
		Path installed = context.installedManifestFile();
		boolean cataloguePresent = validateStoredFile(catalogue, "Stored complete catalogue");
		boolean manifestPresent = validateStoredFile(installed, "Stored selected manifest");

		if (!cataloguePresent) {
			if (manifestPresent) throw new IOException("Stored selected manifest has no complete catalogue");
			return;
		}

		GenerationRecord storedCatalogue;
		try {
			storedCatalogue = ModpackContentTools.readGenerationRecord(catalogue);
		} catch (RuntimeException e) {
			throw new IOException("Stored complete catalogue is invalid", e);
		}
		if (storedCatalogue == null) throw new IOException("Stored complete catalogue is invalid");
		GenerationTarget storedCatalogueTarget = GenerationTarget.from(storedCatalogue);
		if (storedCatalogueTarget.equals(target) && !storedCatalogue.equals(completeRecord))
			throw new IOException("Stored complete catalogue disagrees with transaction target");
		if (!storedCatalogue.manifest().modpackId().equals(transaction.modpackId))
			throw new IOException("Stored complete catalogue belongs to another modpack lineage");
		if (!manifestPresent) {
			if (storedCatalogueTarget.equals(target)) return;
			throw new IOException("Stored complete catalogue has no selected manifest");
		}

		Jsons.ModpackContentFields storedTarget;
		try {
			storedTarget = ConfigTools.read(installed, Jsons.ModpackContentFields.class).orElse(null);
			if (storedTarget == null) throw new IllegalArgumentException("Selected manifest is missing");
			validateManifest(storedTarget, transaction.modpackId);
		} catch (RuntimeException e) {
			throw new IOException("Stored selected manifest is invalid", e);
		}
		GenerationTarget storedIdentity;
		try {
			storedIdentity = GenerationTarget.fromFlat(storedTarget);
		} catch (RuntimeException e) {
			throw new IOException("Stored selected manifest generation identity is invalid", e);
		}

		if (storedCatalogueTarget.equals(target) && !storedIdentity.equals(target)) return;
		if (!storedIdentity.equals(storedCatalogueTarget))
			throw new IOException("Stored selected manifest generation identity disagrees with its catalogue");
		validateStoredManifestComposition(storedCatalogue, storedCatalogueTarget, storedTarget, transaction);
	}

	private static boolean validateStoredFile(Path path, String description) throws IOException {
		if (path == null) throw new IOException(description + " path is missing");
		if (Files.isSymbolicLink(path)) throw new IOException(description + " may not be a symbolic link");
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(description + " must be a regular file");
		return true;
	}

	private void validateStoredManifestComposition(GenerationRecord storedCatalogue, GenerationTarget storedCatalogueTarget,
			Jsons.ModpackContentFields storedTarget, UpdateTransaction transaction) throws IOException {
		try {
			ClientPlatform platform = transaction.platform();
			ResolvedSelection storedSelection = GroupSelectionResolver.resolve(storedCatalogue.manifest(),
					new SelectionIntent(storedTarget.selectedGroups), platform);
			Jsons.ModpackContentFields recomposed = SelectedTreeComposer.compose(storedCatalogue.manifest(), storedSelection, storedCatalogueTarget);
			recomposed.ownershipLedger = storedCatalogue.ownershipLedger().toFields();
			if (!flatManifestState(recomposed).equals(flatManifestState(storedTarget)))
				throw new IOException("Stored selected manifest does not compose from the stored catalogue");
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Stored selected manifest selection is invalid", e);
		}
	}

	private static boolean isCanonicalIntentList(List<String> values) {
		return values != null && values.equals(values.stream().distinct().sorted().toList());
	}

	private static List<String> flatManifestState(Jsons.ModpackContentFields manifest) {
		List<String> state = new ArrayList<>();
		state.add("modpackId=" + Objects.toString(manifest.modpackId, ""));
		state.add("modpackName=" + Objects.toString(manifest.modpackName, ""));
		state.add("automodpackVersion=" + Objects.toString(manifest.automodpackVersion, ""));
		state.add("loader=" + Objects.toString(manifest.loader, ""));
		state.add("loaderVersion=" + Objects.toString(manifest.loaderVersion, ""));
		state.add("mcVersion=" + Objects.toString(manifest.mcVersion, ""));
		state.add("targetGenerationId=" + Objects.toString(manifest.targetGenerationId, ""));
		state.add("parentGenerationId=" + Objects.toString(manifest.parentGenerationId, ""));
		state.add("stateDigest=" + Objects.toString(manifest.stateDigest, ""));
		if (manifest.selectedGroups != null) manifest.selectedGroups.stream().sorted().forEach(group -> state.add("group=" + group));
		if (manifest.list != null)
			manifest.list.stream().sorted(Comparator.comparing(item -> normalizeUnchecked(item.file))).forEach(item -> state.add(String.join("\0",
					"file", normalizeUnchecked(item.file), Objects.toString(item.size, ""), Objects.toString(item.type, ""), Boolean.toString(item.editable),
					Boolean.toString(item.overwriteEditable), Boolean.toString(item.forceCopy), Objects.toString(item.sha1, ""), Objects.toString(item.murmur, ""))));
		state.add("ledgerDigest=" + Objects.toString(manifest.ownershipLedger == null ? null : manifest.ownershipLedger.digest, ""));
		return state;
	}

	private static String normalizeUnchecked(String path) {
		try {
			return UpdatePlanner.normalize(path);
		} catch (RuntimeException e) {
			return Objects.toString(path, "");
		}
	}

	private void validatePlannedClientConfig(UpdateTransaction transaction) throws IOException {
		Jsons.ClientConfigFieldsV3 config = transaction.plannedClientConfig;
		if (!transaction.modpackId.equals(config.selectedModpackId) || config.modpackConnections == null)
			throw new IOException("Planned client config does not select the transaction modpack");
		Jsons.ConnectionInfo connection = config.modpackConnections.get(transaction.modpackId);
		if (connection == null || !connection.isComplete()) throw new IOException("Planned client config has no complete selected connection");
	}

	private void validateOrderedMetadata(UpdateTransaction transaction) throws IOException {
		if (transaction.restartReasons.stream().anyMatch(Objects::isNull)
				|| new LinkedHashSet<>(transaction.restartReasons).size() != transaction.restartReasons.size())
			throw new IOException("Invalid restart reasons");
		if (!transaction.restartReasons.equals(transaction.restartReasons.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList()))
			throw new IOException("Restart reasons are not ordered");
	}

	private Map<FileKey, ProjectedFile> validateFinalState(List<ProjectedFile> entries, String modpackId, UpdateTransaction.Purpose purpose) throws IOException {
		Map<FileKey, ProjectedFile> finalState = new LinkedHashMap<>();
		Set<Path> physicalTargets = new HashSet<>();
		FileKey previous = null;
		for (ProjectedFile entry : entries) {
			if (entry == null || entry.root() == null) throw new IOException("Incomplete projected final-state entry");
			if (purpose == UpdateTransaction.Purpose.SELF_UPDATE) {
				Path selfUpdatePath = Path.of(entry.relativePath());
				if (entry.root() != Root.MODS_DIR || selfUpdatePath.getNameCount() != 1
						|| !selfUpdatePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
					throw new IOException("Self-update projected state is restricted to direct JAR children of the mods directory");
			} else
				if (purpose == UpdateTransaction.Purpose.LEGACY_DUMMY_CLEANUP
						&& (entry.present() || (entry.root() != Root.GAME_DIR && entry.root() != Root.MODS_DIR && entry.root() != Root.AUTOMODPACK_DIR))) {
							throw new IOException("Legacy dummy cleanup projected state is restricted to constrained absences");
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
			} else if (entry.expectedHash() != null || entry.expectedSize() != -1) {
				throw new IOException("Projected absence has file metadata");
			}
			finalState.put(key, entry);
		}
		return finalState;
	}

	private void validateBaselineCaptures(UpdateTransaction transaction) throws IOException {
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE) {
			if (!transaction.plannedBaselineCaptures.isEmpty()) throw new IOException("Only modpack updates can capture baselines");
			return;
		}
		List<BaselineCapture> sorted = transaction.plannedBaselineCaptures.stream()
				.sorted(Comparator.comparing((BaselineCapture capture) -> capture.root().ordinal()).thenComparing(BaselineCapture::relativePath)).toList();
		if (!transaction.plannedBaselineCaptures.equals(sorted)) throw new IOException("Baseline captures are not deterministically ordered");
		Set<FileKey> seen = new HashSet<>();
		for (BaselineCapture capture : transaction.plannedBaselineCaptures) {
			if (capture == null || capture.root() == null) throw new IOException("Incomplete baseline capture");
			if (capture.root() != Root.GAME_DIR && capture.root() != Root.MODS_DIR)
				throw new IOException("Baseline root is outside managed live files");
			String relative = normalizeOperationPath(capture.relativePath());
			FileKey key = new FileKey(capture.root(), relative);
			if (!seen.add(key)) throw new IOException("Duplicate baseline capture target");
			validateRootAndPath(capture.root(), relative, transaction.modpackId, transaction.purpose);
			if (capture.absent()) {
				if (!capture.expectedHash().isEmpty() || capture.expectedSize() != -1) throw new IOException("Absent baseline contains file metadata");
			} else {
				validateHash(capture.expectedHash(), "baseline SHA-1");
				if (capture.expectedSize() < 0) throw new IOException("Invalid baseline size");
			}
			boolean hasMutation = false;
			for (Operation operation : transaction.operations) {
				if ((operation.operation() != OperationType.INSTALL_OBJECT && operation.operation() != OperationType.DELETE)
						|| operation.root() != capture.root())
					continue;
				if (normalizeOperationPath(operation.relativePath()).equals(relative)) {
					hasMutation = true;
					break;
				}
			}
			if (!hasMutation) throw new IOException("Baseline capture has no matching live mutation");
		}
	}

	private void validatePreservations(UpdateTransaction transaction, Map<FileKey, ProjectedFile> finalState,
			Jsons.ModpackContentFields manifest) throws IOException {
		boolean removal = transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL;
		if (transaction.purpose != UpdateTransaction.Purpose.MODPACK_UPDATE && !removal) {
			if (!transaction.plannedPreservations.isEmpty()) throw new IOException("Only modpack transactions can preserve deleted files");
			return;
		}
		if (manifest == null) throw new IOException("Preservation validation has no target manifest");
		OwnershipLedger ledger;
		try {
			ledger = OwnershipLedger.fromFields(manifest.ownershipLedger);
		} catch (RuntimeException e) {
			throw new IOException("Preservation validation has an invalid ownership ledger", e);
		}
		Set<String> targetPaths = new HashSet<>();
		for (var item : manifest.list) targetPaths.add(normalizeManifestPath(item.file));
		List<Preservation> sorted = transaction.plannedPreservations.stream()
				.sorted(Comparator.comparing((Preservation preservation) -> preservation.root().ordinal()).thenComparing(Preservation::relativePath)).toList();
		if (!transaction.plannedPreservations.equals(sorted)) throw new IOException("Preservation entries are not deterministically ordered");
		Set<FileKey> seen = new HashSet<>();
		for (Preservation preservation : transaction.plannedPreservations) {
			if (preservation == null || preservation.root() == null) throw new IOException("Incomplete preservation entry");
			if (preservation.root() != Root.GAME_DIR && preservation.root() != Root.MODS_DIR)
				throw new IOException("Preservation root is outside managed live files");
			String relative;
			try {
				relative = normalizeOperationPath(preservation.relativePath());
			} catch (RuntimeException e) {
				throw new IOException("Invalid preservation path", e);
			}
			FileKey key = new FileKey(preservation.root(), relative);
			if (!seen.add(key)) throw new IOException("Duplicate preservation target");
			validateRootAndPath(preservation.root(), relative, transaction.modpackId, transaction.purpose);
			validateHash(preservation.expectedHash(), "preservation SHA-1");
			if (preservation.expectedSize() < 0) throw new IOException("Invalid preservation size");
			String logicalPath = preservation.root() == Root.MODS_DIR ? "mods/" + relative : relative;
			if (!removal && targetPaths.contains(logicalPath)) throw new IOException("Preservation target remains in the selected target");
			OwnershipLedger.Entry ledgerEntry = ledger.entries().get(logicalPath);
			if (ledgerEntry == null || !ledgerEntry.historicalHashes().contains(new OwnershipLedger.Content(preservation.expectedHash().toLowerCase(Locale.ROOT), preservation.expectedSize())))
				throw new IOException("Preservation target is not owned by the target ledger");
			ProjectedFile projected = finalState.get(key);
			if (projected == null || projected.present()) throw new IOException("Preservation target is not absent from projected final state");
			Operation deletion = null;
			for (Operation operation : transaction.operations) {
				if (operation.operation() != OperationType.DELETE || operation.root() != preservation.root()) continue;
				if (normalizeOperationPath(operation.relativePath()).equals(relative)) {
					deletion = operation;
					break;
				}
			}
			if (deletion == null || deletion.expectedExistingHash() == null
					|| !deletion.expectedExistingHash().equalsIgnoreCase(preservation.expectedHash()))
				throw new IOException("Preservation target has no matching guarded deletion");
		}
	}

	private void validateInstall(Operation operation, ProjectedFile projected) throws IOException {
		if (operation.root() == Root.STORE_DIR) throw new IOException("Invalid install operation root/metadata");
		validateHash(operation.expectedObjectHash(), "install SHA-1");
		if (operation.expectedExistingHash() != null) validateHash(operation.expectedExistingHash(), "install expected SHA-1");
		if (operation.expectedSize() < 0 || !projected.present() || operation.expectedSize() != projected.expectedSize()
				|| !operation.expectedObjectHash().equalsIgnoreCase(projected.expectedHash()))
			throw new IOException("Install operation does not match projected final state");
		Path source = context.storeDirectory().resolve(operation.expectedObjectHash());
		if (!SmartFileUtils.isValidFile(source, operation.expectedSize(), operation.expectedObjectHash()))
			throw new IOException("Required CAS object is missing or corrupt: " + operation.expectedObjectHash());
	}

	private void validateDelete(Operation operation, ProjectedFile projected) throws IOException {
		if (operation.root() == Root.STORE_DIR || operation.expectedObjectHash() != null || operation.expectedSize() != -1 || projected.present())
			throw new IOException("Invalid delete operation root/metadata");
		if (operation.expectedExistingHash() != null) validateHash(operation.expectedExistingHash(), "deletion expected SHA-1");
	}

	private static void validateDirectoryOperation(Operation operation) throws IOException {
		if (operation.root() == Root.STORE_DIR || operation.expectedObjectHash() != null || operation.expectedExistingHash() != null || operation.expectedSize() != -1)
			throw new IOException("Invalid directory operation metadata");
	}

	private void validateManifestProjection(Jsons.ModpackContentFields manifest, Map<FileKey, ProjectedFile> finalState) throws IOException {
		for (var item : manifest.list) {
			String relative = normalizeManifestPath(item.file);
			ProjectedFile projected = finalState.get(new FileKey(Root.MODPACK_DIR, relative));
			if (projected == null || !projected.present()) throw new IOException("Manifest file is absent from projected final state: " + relative);
			if (!item.editable && (!item.sha1.equalsIgnoreCase(projected.expectedHash()) || parseNonnegativeSize(item.size) != projected.expectedSize()))
				throw new IOException("Manifest file does not match projected final state: " + relative);
		}
	}

	private Execution executePersisted(UpdateTransaction transaction) throws IOException {
		Operation current = null;
		Path blockedPath = null;
		try {
			for (Operation operation : transaction.operations) {
				if (operation.operation() == OperationType.CREATE_DIRECTORY) {
					current = operation;
					Files.createDirectories(resolve(operation));
				}
			}
			if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) captureBaselines(transaction);
			List<SmartFileUtils.CopyRequest> copies = new ArrayList<>();
			for (Operation operation : transaction.operations) {
				if (operation.operation() != OperationType.INSTALL_OBJECT) continue;
				current = operation;
				Path target = resolve(operation);
				if (SmartFileUtils.isValidFile(target, operation.expectedSize(), operation.expectedObjectHash())) continue;
				if (operation.expectedExistingHash() != null) {
					long targetSize = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? Files.size(target) : -1;
					if (!SmartFileUtils.isValidFile(target, targetSize, operation.expectedExistingHash()))
						throw new IOException("Restore target changed after planning: " + target);
				}
				Path source = context.storeDirectory().resolve(operation.expectedObjectHash());
				copies.add(new SmartFileUtils.CopyRequest(source, target, operation.expectedSize(), operation.expectedObjectHash()));
			}
			if (!copies.isEmpty()) {
				try {
					SmartFileUtils.copyVerifiedAtomicBatch(copies, COPY_CONCURRENCY);
				} catch (SmartFileUtils.CopyBatchException e) {
					blockedPath = e.target();
					for (Operation operation : transaction.operations) {
						if (operation.operation() == OperationType.INSTALL_OBJECT && resolve(operation).equals(blockedPath)) {
							current = operation;
							break;
						}
					}
					throw e;
				}
			}
			for (Operation operation : transaction.operations) {
				if (operation.operation() != OperationType.INSTALL_OBJECT) continue;
				current = operation;
				if (!SmartFileUtils.isValidFile(resolve(operation), operation.expectedSize(), operation.expectedObjectHash()))
					throw new IOException("Installed file failed verification: " + resolve(operation));
			}
			for (Preservation preservation : transaction.plannedPreservations) {
				blockedPath = resolve(preservation.root(), preservation.relativePath());
				preserveObject(preservation);
				blockedPath = null;
			}
			for (Operation operation : transaction.operations) {
				if (operation.operation() != OperationType.DELETE) continue;
				current = operation;
				Path target = resolve(operation);
				boolean targetExists = transaction.purpose == UpdateTransaction.Purpose.LEGACY_DUMMY_CLEANUP
						? Files.exists(target, LinkOption.NOFOLLOW_LINKS)
						: Files.exists(target);
				if (targetExists) {
					if (transaction.purpose == UpdateTransaction.Purpose.LEGACY_DUMMY_CLEANUP) {
						if (!LegacyDummyFiles.matches(target)) throw new IOException("Legacy dummy cleanup target no longer matches the known signature: " + target);
					} else if (operation.expectedExistingHash() == null || !operation.expectedExistingHash().equalsIgnoreCase(HashUtils.getHash(target))) {
						throw new IOException("Deletion target changed after planning: " + target);
					}
					Files.delete(target);
				}
			}
			for (Operation operation : transaction.operations) {
				if (operation.operation() != OperationType.REMOVE_EMPTY_DIRECTORY) continue;
				current = operation;
				Path target = resolve(operation);
				if (SmartFileUtils.isEmptyDirectory(target)) Files.deleteIfExists(target);
			}
			verifyFinalState(transaction.projectedFinalState, transaction.purpose);
			if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
				ConfigTools.writeAtomic(context.clientConfigFile(), transaction.plannedClientConfig);
				if (context.beforeManifestAction() != null && !Files.exists(context.completeCatalogueFile(), LinkOption.NOFOLLOW_LINKS))
					context.beforeManifestAction().run(transaction);
				blockedPath = context.completeCatalogueFile();
				GenerationRecord complete = transaction.completeGenerationRecord();
				ConfigTools.writeAtomic(context.completeCatalogueFile(), complete.toFields());
				blockedPath = null;
				verifyFinalState(transaction.projectedFinalState, transaction.purpose);
				ModpackContentTools.write(context.installedManifestFile(), transaction.targetManifest());
			} else if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
				ConfigTools.writeAtomic(context.clientConfigFile(), transaction.plannedClientConfig);
				current = null;
				removeModpackMetadata();
			} else if (transaction.purpose == UpdateTransaction.Purpose.LEGACY_DUMMY_CLEANUP) {
				current = null;
				blockedPath = context.automodpackDirectory().resolve("automodpack-dummy-files.json");
				pruneLegacyDummyRegistry(transaction.operations);
				blockedPath = null;
			}
			if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE || transaction.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL) {
				blockedPath = context.selectionFile();
				claimSelection(transaction);
				blockedPath = null;
			}
			Files.deleteIfExists(context.transactionFile());
			Files.deleteIfExists(context.transactionResultFile());
			return new Execution(UpdateTransactionResult.Status.SUCCESS, transaction, null, null, null);
		} catch (IOException e) {
			if (blockedPath == null && current != null) blockedPath = resolve(current);
			if (isLockFailure(e)) {
				UpdateTransactionResult result = new UpdateTransactionResult(transaction.transactionId, UpdateTransactionResult.Status.DEFERRED_LOCKED,
						current == null ? null : current.operation().name(), blockedPath == null ? null : blockedPath.toString(), e.getMessage());
				ConfigTools.writeAtomic(context.transactionResultFile(), result);
				return new Execution(UpdateTransactionResult.Status.DEFERRED_LOCKED, transaction, current == null ? null : current.operation().name(), blockedPath,
						e.getMessage());
			}
			throw new UpdateExecutionException(current == null ? null : current.operation().name(), blockedPath, e);
		}
	}

	private void removeModpackMetadata() throws IOException {
		Path baseline = context.modpackDirectory().resolve(modpackBaselineFileName).toAbsolutePath().normalize();
		Path installedManifest = context.installedManifestFile().toAbsolutePath().normalize();
		Path completeCatalogue = context.completeCatalogueFile().toAbsolutePath().normalize();
		for (Path metadata : List.of(baseline, installedManifest, completeCatalogue)) {
			validateNoSymbolicLinkDescendants(context.automodpackDirectory(), metadata);
			Files.deleteIfExists(metadata);
		}
	}

	private void captureBaselines(UpdateTransaction transaction) throws IOException {
		if (transaction.plannedBaselineCaptures.isEmpty()) return;
		Path baselinePath = context.modpackDirectory().resolve(modpackBaselineFileName).toAbsolutePath().normalize();
		validateNoSymbolicLinkDescendants(context.automodpackDirectory(), baselinePath);
		Jsons.ClientBaselineFields baseline = readBaseline(baselinePath, transaction.modpackId);
		Map<String, Jsons.ClientBaselineFields.EntryFields> entries = new TreeMap<>();
		for (Jsons.ClientBaselineFields.EntryFields entry : baseline.entries) entries.put(entry.logicalPath, entry);
		String sourceGenerationId = "";
		Jsons.ModpackContentFields installed = ConfigTools.read(context.installedManifestFile(), Jsons.ModpackContentFields.class).orElse(null);
		if (installed != null && installed.targetGenerationId != null && !installed.targetGenerationId.isEmpty()) sourceGenerationId = installed.targetGenerationId;
		boolean changed = false;
		for (BaselineCapture capture : transaction.plannedBaselineCaptures) {
			String logicalPath = capture.root() == Root.MODS_DIR ? "mods/" + capture.relativePath() : capture.relativePath();
			if (entries.containsKey(logicalPath)) continue;
			Path source = resolve(capture.root(), capture.relativePath());
			Jsons.ClientBaselineFields.EntryFields entry = new Jsons.ClientBaselineFields.EntryFields();
			entry.logicalPath = logicalPath;
			entry.baselineGenerationId = sourceGenerationId;
			if (capture.absent()) {
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Baseline path was expected to be absent: " + source);
				entry.absent = true;
				entry.objectHash = "";
				entry.size = -1;
			} else {
				if (!SmartFileUtils.isValidFile(source, capture.expectedSize(), capture.expectedHash()))
					throw new IOException("Baseline source failed size/SHA-1 verification: " + source);
				Path object = context.storeDirectory().resolve(capture.expectedHash()).toAbsolutePath().normalize();
				Path storeRoot = context.storeDirectory().toAbsolutePath().normalize();
				if (!object.startsWith(storeRoot)) throw new IOException("Baseline object escapes the client CAS");
				validateNoSymbolicLinkDescendants(storeRoot, object);
				SmartFileUtils.copyVerifiedAtomic(source, object, capture.expectedSize(), capture.expectedHash());
				if (!SmartFileUtils.isValidFile(object, capture.expectedSize(), capture.expectedHash()))
					throw new IOException("Baseline CAS object failed verification: " + object);
				entry.absent = false;
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
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Baseline manifest is not a regular file");
		Jsons.ClientBaselineFields baseline;
		try {
			baseline = ConfigTools.read(path, Jsons.ClientBaselineFields.class).orElseThrow(() -> new IOException("Baseline manifest is empty"));
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new IOException("Baseline manifest is invalid", e);
		}
		if (baseline.schemaVersion != 1 || !modpackId.equals(baseline.modpackId) || baseline.entries == null)
			throw new IOException("Baseline manifest identity is invalid");
		Set<String> paths = new HashSet<>();
		for (Jsons.ClientBaselineFields.EntryFields entry : baseline.entries) {
			if (entry == null || entry.logicalPath == null || !paths.add(entry.logicalPath)) throw new IOException("Baseline manifest has duplicate or incomplete entries");
			String normalized = UpdatePlanner.normalize(entry.logicalPath);
			if (!normalized.equals(entry.logicalPath)) throw new IOException("Baseline manifest path is not normalized");
			if (entry.absent) {
				if (!entry.objectHash.isEmpty() || entry.size != -1) throw new IOException("Absent baseline entry contains file metadata");
			} else {
				validateHash(entry.objectHash, "baseline SHA-1");
				if (entry.size < 0) throw new IOException("Baseline entry has invalid size");
			}
			if (!entry.baselineGenerationId.isEmpty() && !SHA1.matcher(entry.baselineGenerationId).matches())
				throw new IOException("Baseline entry has invalid source generation ID");
		}
		return baseline;
	}

	private void preserveObject(Preservation preservation) throws IOException {
		Path source = resolve(preservation.root(), preservation.relativePath());
		Path object = context.storeDirectory().resolve(preservation.expectedHash()).toAbsolutePath().normalize();
		Path storeRoot = context.storeDirectory().toAbsolutePath().normalize();
		if (!object.startsWith(storeRoot)) throw new IOException("Preservation object escapes the client CAS");
		validateNoSymbolicLinkDescendants(storeRoot, object);
		boolean objectValid = SmartFileUtils.isValidFile(object, preservation.expectedSize(), preservation.expectedHash());
		boolean sourcePresent = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
		if (sourcePresent && !SmartFileUtils.isValidFile(source, preservation.expectedSize(), preservation.expectedHash()))
			throw new IOException("Preservation source changed after planning: " + source);
		if (!objectValid) {
			if (!sourcePresent) throw new IOException("Preservation source is missing: " + source);
			SmartFileUtils.copyVerifiedAtomic(source, object, preservation.expectedSize(), preservation.expectedHash());
		}
		if (!SmartFileUtils.isValidFile(object, preservation.expectedSize(), preservation.expectedHash()))
			throw new IOException("Preserved CAS object failed verification: " + object);
	}

	private void verifyFinalState(List<ProjectedFile> finalState, UpdateTransaction.Purpose purpose) throws IOException {
		for (ProjectedFile projected : finalState) {
			Path target = resolve(projected.root(), projected.relativePath());
			if (projected.present()) {
				if (!SmartFileUtils.isValidFile(target, projected.expectedSize(), projected.expectedHash()))
					throw new IOException("Projected final target verification failed: " + target);
			} else
				if (purpose == UpdateTransaction.Purpose.LEGACY_DUMMY_CLEANUP
						? Files.exists(target, LinkOption.NOFOLLOW_LINKS)
						: Files.exists(target)) {
							throw new IOException("Projected absent target exists: " + target);
						}
		}
	}

	private void pruneLegacyDummyRegistry(List<Operation> operations) throws IOException {
		Path registryPath = context.automodpackDirectory().resolve("automodpack-dummy-files.json");
		Jsons.ClientDummyFiles registry;
		try {
			registry = ConfigTools.read(registryPath, Jsons.ClientDummyFiles.class).orElse(null);
		} catch (RuntimeException e) {
			throw new IOException("Failed to read legacy dummy registry", e);
		}
		if (registry == null) return;
		if (registry.files == null) registry.files = new LinkedHashSet<>();
		Set<Path> completedTargets = new HashSet<>();
		for (Operation operation : operations) completedTargets.add(resolve(operation));
		Set<String> remaining = new LinkedHashSet<>();
		for (String entry : registry.files) {
			Path registered = resolveLegacyRegistryEntry(entry);
			if (registered == null || !completedTargets.contains(registered) || Files.exists(registered, LinkOption.NOFOLLOW_LINKS)) remaining.add(entry);
		}
		if (!remaining.equals(registry.files)) {
			registry.files = remaining;
			ConfigTools.writeAtomic(registryPath, registry);
		}
		if (remaining.isEmpty()) Files.deleteIfExists(registryPath);
	}

	private Path resolveLegacyRegistryEntry(String entry) {
		if (entry == null || entry.isBlank() || entry.indexOf('\0') >= 0) return null;
		try {
			Path parsed = Path.of(entry);
			Path resolved = (parsed.isAbsolute() ? parsed : context.gameDirectory().resolve(parsed)).toAbsolutePath().normalize();
			Path gameDirectory = context.gameDirectory().toAbsolutePath().normalize();
			return resolved.startsWith(gameDirectory) ? resolved : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private Path resolve(Operation operation) throws IOException {
		return resolve(operation.root(), operation.relativePath());
	}

	private Path resolve(Root operationRoot, String relativePath) throws IOException {
		Path root = root(operationRoot).toAbsolutePath().normalize();
		Path resolved = root.resolve(normalizeOperationPath(relativePath)).normalize();
		if (!resolved.startsWith(root)) throw new IOException("Operation escapes constrained root");
		validateNoSymbolicLinkDescendants(root, resolved);
		return resolved;
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

	private Path root(Root root) throws IOException {
		Path path = switch (root) {
			case MODPACK_DIR -> context.modpackDirectory();
			case GAME_DIR -> context.gameDirectory();
			case MODS_DIR -> context.modsDirectory();
			case STORE_DIR -> context.storeDirectory();
			case AUTOMODPACK_DIR -> context.automodpackDirectory();
		};
		if (path == null) throw new IOException("Transaction context does not provide root " + root);
		return path;
	}

	private Path validateRootAndPath(Root root, String relativePath, String currentModpackId, UpdateTransaction.Purpose purpose) throws IOException {

		if (root == Root.STORE_DIR) throw new IOException("Transactions may not mutate the content-addressed store");
		Path constrainedRoot = root(root).toAbsolutePath().normalize();
		Path resolved = constrainedRoot.resolve(relativePath).normalize();
		if (!resolved.startsWith(constrainedRoot)) throw new IOException("Transaction path escapes constrained root");
		validateNoSymbolicLinkDescendants(constrainedRoot, resolved);
		if (root == Root.GAME_DIR && (resolved.startsWith(context.modsDirectory().toAbsolutePath().normalize())
				|| resolved.startsWith(context.automodpackDirectory().toAbsolutePath().normalize())))
			throw new IOException("GAME_DIR operation must use the narrower constrained root");
		if (isProtectedManifestPath(resolved)) throw new IOException("Authoritative modpack metadata may only be published by the executor");
		if (root == Root.AUTOMODPACK_DIR) {
			if (purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
				validateModpackStoragePath(relativePath, currentModpackId);
			} else if (purpose == UpdateTransaction.Purpose.LEGACY_DUMMY_CLEANUP) {
				Path relative = Path.of(relativePath);
				if (relative.startsWith("store") || relative.startsWith(".private") || relative.startsWith(Path.of("cache", "update-helper"))
						|| relative.equals(Path.of("automodpack-dummy-files.json")))
					throw new IOException("Legacy dummy cleanup target is protected AutoModpack state");
			}
		}
		return resolved;
	}

	private boolean isProtectedManifestPath(Path resolved) {
		Path target = resolved.toAbsolutePath().normalize();
		if (context.installedManifestFile() != null && target.equals(context.installedManifestFile().toAbsolutePath().normalize())) return true;
		if (context.completeCatalogueFile() != null && target.equals(context.completeCatalogueFile().toAbsolutePath().normalize())) return true;
		if (context.modpackDirectory() == null) return false;
		Path modpackDirectory = context.modpackDirectory().toAbsolutePath().normalize();
		return target.equals(modpackDirectory.resolve(modpackContentFileName)) || target.equals(modpackDirectory.resolve(modpackCatalogueFileName));
	}

	private static void validateModpackStoragePath(String relativePath, String currentModpackId) throws IOException {
		Path path = Path.of(relativePath);
		if (path.getNameCount() < 3 || !"modpacks".equals(path.getName(0).toString()) || !ModpackId.isValid(path.getName(1).toString()))
			throw new IOException("AUTOMODPACK_DIR operations must target stable modpack storage");
		if (currentModpackId.equals(path.getName(1).toString()))
			throw new IOException("Current modpack files must use MODPACK_DIR rather than AUTOMODPACK_DIR");
	}

	private static String normalizeManifestPath(String path) throws IOException {
		try {
			return UpdatePlanner.normalize(path);
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsafe manifest path", e);
		}
	}

	private static String normalizeOperationPath(String relativePath) throws IOException {
		if (relativePath == null || relativePath.startsWith("/") || relativePath.startsWith("\\")
				|| relativePath.matches("^[A-Za-z]:[\\\\/].*"))
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
				String detail = String.join(" ", Objects.toString(fileSystemException.getReason(), ""), Objects.toString(fileSystemException.getMessage(), ""))
						.toLowerCase(Locale.ROOT);
				if (detail.contains("used by another process") || detail.contains("being used by another process") || detail.contains("sharing violation")) return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
