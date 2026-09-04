package pl.skidam.automodpack_core.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.BaselineCapture;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.HashUtils;

/** The single write-ahead record for one client update. It stores intent, operations, and its target's ledger, never filesystem paths or duplicated manifests. */
public final class UpdateTransaction {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public int schemaVersion;
	public String transactionId;
	public Purpose purpose;
	public Phase phase;
	public String modpackId;
	public String contentToken;
	public String policySha1;
	public String ledgerDigest;
	public GenerationJsons.OwnershipLedgerFields ownershipLedger;
	public String targetPlatform;
	public String selectionDigest;
	public String overlayDigest;
	public ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig;
	public boolean expectedPriorSelectionPresent;
	public List<String> expectedPriorRequestedGroups;
	public List<String> expectedPriorRequestedCategories;
	public List<String> expectedPriorExcludedGroups;
	public List<String> requestedGroups;
	public List<String> requestedCategories;
	public List<String> excludedGroups;
	public List<Operation> operations;
	public List<ProjectedFile> projectedFinalState;
	public ClientConfigJsons.ClientConfigFieldsV3 plannedClientConfig;
	public List<RestartReason> restartReasons;
	public List<Preservation> plannedPreservations;
	public List<BaselineCapture> plannedBaselineCaptures;
	public List<Conflict> plannedConflicts;
	public String plannedConsequencesDigest;
	public ClientStorageJsons.ClientGeneratedCopiesFields plannedGeneratedCopies;
	public Status resultStatus;
	public String resultOperation;
	public String resultPath;
	public String resultMessage;

	public UpdateTransaction() {}

	public static UpdateTransaction create(UpdatePlan plan, SelectedModpackTarget target, String overlayDigest,
			ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(target, "target");
		if (!plan.modpackId().equals(target.manifest().modpackId())) throw new IllegalArgumentException("Plan and selected target modpack IDs disagree");
		if (!plan.packTarget().equals(target.packTarget())) throw new IllegalArgumentException("Plan and selected target generation identities disagree");
		if (!plan.packTarget().equals(PackTarget.fromFlat(target.flatTarget())))
			throw new IllegalArgumentException("Plan and selected flat target generation identities disagree");

		UpdateTransaction transaction = base(Purpose.MODPACK_UPDATE);
		fillGeneration(transaction, plan.packTarget(), target.document().ownershipLedger());
		transaction.targetPlatform = target.platform().id();
		transaction.expectedPriorSelectionPresent = target.expectedPriorIntent() != null;
		transaction.expectedPriorRequestedGroups = intentValues(target.expectedPriorIntent(), IntentPart.GROUPS);
		transaction.expectedPriorRequestedCategories = intentValues(target.expectedPriorIntent(), IntentPart.CATEGORIES);
		transaction.expectedPriorExcludedGroups = intentValues(target.expectedPriorIntent(), IntentPart.EXCLUDED);
		transaction.requestedGroups = new ArrayList<>(target.selection().intent().requestedGroups());
		transaction.requestedCategories = new ArrayList<>(target.selection().intent().requestedCategories());
		transaction.excludedGroups = new ArrayList<>(target.selection().intent().excludedGroups());
		transaction.selectionDigest = digest(target.selection().intent());
		transaction.overlayDigest = overlayDigest == null ? "" : overlayDigest;
		transaction.expectedClientConfig = copyConfig(expectedClientConfig);
		fillPlan(transaction, plan);
		transaction.plannedGeneratedCopies = GeneratedCopyState.fromCopies(plan.modpackId(), plan.packTarget().contentToken(), digest(target.selection().intent()), plan.generatedCopies()).toFields();
		return transaction;
	}

	public static UpdateTransaction createRemoval(UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent, GenerationJsons.OwnershipLedgerFields ownershipLedger,
			String overlayDigest, ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		return createRemovalLike(Purpose.MODPACK_REMOVAL, plan, platform, expectedPriorIntent, ownershipLedger, overlayDigest, expectedClientConfig);
	}

	public static UpdateTransaction createDeactivation(UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent, GenerationJsons.OwnershipLedgerFields ownershipLedger,
			String overlayDigest, ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		return createRemovalLike(Purpose.MODPACK_DEACTIVATION, plan, platform, expectedPriorIntent, ownershipLedger, overlayDigest, expectedClientConfig);
	}

	private static UpdateTransaction createRemovalLike(Purpose purpose, UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent,
			GenerationJsons.OwnershipLedgerFields ownershipLedger, String overlayDigest, ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(platform, "platform");
		UpdateTransaction transaction = base(purpose);
		fillGeneration(transaction, plan.packTarget(), OwnershipLedger.fromFields(ownershipLedger));
		transaction.targetPlatform = platform.id();
		transaction.expectedPriorSelectionPresent = expectedPriorIntent != null;
		transaction.expectedPriorRequestedGroups = intentValues(expectedPriorIntent, IntentPart.GROUPS);
		transaction.expectedPriorRequestedCategories = intentValues(expectedPriorIntent, IntentPart.CATEGORIES);
		transaction.expectedPriorExcludedGroups = intentValues(expectedPriorIntent, IntentPart.EXCLUDED);
		transaction.requestedGroups = List.of();
		transaction.requestedCategories = List.of();
		transaction.excludedGroups = List.of();
		transaction.selectionDigest = digest(expectedPriorIntent);
		transaction.overlayDigest = overlayDigest == null ? "" : overlayDigest;
		transaction.expectedClientConfig = copyConfig(expectedClientConfig);
		fillPlan(transaction, plan);
		return transaction;
	}

	public static UpdateTransaction createSelfUpdate(String currentPath, String targetPath, String targetHash, long targetSize, String currentHash) {
		UpdateTransaction transaction = base(Purpose.SELF_UPDATE);
		List<Operation> operations = new ArrayList<>();
		operations.add(new Operation(Root.GAME_DIR, targetPath, OperationType.INSTALL_OBJECT, targetHash, targetSize, null));
		List<ProjectedFile> finalState = new ArrayList<>();
		finalState.add(new ProjectedFile(Root.GAME_DIR, targetPath, true, targetHash, targetSize));
		if (!currentPath.equals(targetPath)) {
			operations.add(new Operation(Root.GAME_DIR, currentPath, OperationType.DELETE, null, -1, currentHash));
			finalState.add(new ProjectedFile(Root.GAME_DIR, currentPath, false, null, -1));
		}
		sortOperations(operations);
		finalState.sort(Comparator.comparing((ProjectedFile projected) -> projected.root().ordinal()).thenComparing(ProjectedFile::relativePath));
		transaction.operations = List.copyOf(operations);
		transaction.projectedFinalState = List.copyOf(finalState);
		transaction.restartReasons = List.of();
		return transaction;
	}

	/** The pending work must be able to rebuild its target generation offline, so modpack transactions carry its exact ledger. */
	private static void fillGeneration(UpdateTransaction transaction, PackTarget target, OwnershipLedger ledger) {
		transaction.modpackId = target.modpackId();
		transaction.contentToken = target.contentToken();
		transaction.policySha1 = target.policySha1();
		transaction.ledgerDigest = target.ledgerDigest();
		if (!target.modpackId().equals(ledger.modpackId()) || !target.ledgerDigest().equals(ledger.digest()))
			throw new IllegalArgumentException("Transaction generation identity does not match the target ledger");
		transaction.ownershipLedger = ledger.toFields();
	}

	private static void fillPlan(UpdateTransaction transaction, UpdatePlan plan) {
		transaction.operations = List.copyOf(plan.operations());
		transaction.projectedFinalState = List.copyOf(plan.projectedFinalState());
		transaction.plannedClientConfig = plan.plannedClientConfig();
		transaction.restartReasons = new ArrayList<>(new LinkedHashSet<>(plan.restartReasons()));
		transaction.plannedPreservations = List.copyOf(plan.preservations());
		transaction.plannedBaselineCaptures = List.copyOf(plan.baselineCaptures());
		transaction.plannedConflicts = List.copyOf(plan.conflicts());
		transaction.plannedConsequencesDigest = ReviewedUpdatePlan.consequencesDigest(plan.consequences());
	}

	private static UpdateTransaction base(Purpose purpose) {
		UpdateTransaction transaction = new UpdateTransaction();
		transaction.schemaVersion = CURRENT_SCHEMA_VERSION;
		transaction.transactionId = UUID.randomUUID().toString();
		transaction.purpose = purpose;
		transaction.phase = Phase.PLANNED;
		transaction.plannedPreservations = new ArrayList<>();
		transaction.plannedBaselineCaptures = new ArrayList<>();
		transaction.plannedConflicts = new ArrayList<>();
		transaction.plannedGeneratedCopies = null;
		return transaction;
	}

	private static void sortOperations(List<Operation> operations) {
		operations.sort(Operation.ORDER);
	}

	private enum IntentPart {
		GROUPS, CATEGORIES, EXCLUDED
	}

	private static List<String> intentValues(SelectionIntent intent, IntentPart part) {
		if (intent == null) return List.of();
		return switch (part) {
			case GROUPS -> new ArrayList<>(intent.requestedGroups());
			case CATEGORIES -> new ArrayList<>(intent.requestedCategories());
			case EXCLUDED -> new ArrayList<>(intent.excludedGroups());
		};
	}

	public PackTarget packTarget() {
		return new PackTarget(modpackId, contentToken, policySha1, ledgerDigest);
	}

	public ClientPlatform platform() {
		return ClientPlatform.parse(targetPlatform);
	}

	public SelectionIntent expectedPriorIntent() {
		return expectedPriorSelectionPresent ? new SelectionIntent(expectedPriorRequestedGroups, expectedPriorRequestedCategories, expectedPriorExcludedGroups) : null;
	}

	public SelectionIntent targetIntent() {
		return new SelectionIntent(requestedGroups, requestedCategories, excludedGroups);
	}

	public static String digest(SelectionIntent intent) {
		if (intent == null) return "";
		MessageDigest digest = HashUtils.newSha1Digest();
		digest.update("automodpack-selection-v2\n".getBytes(StandardCharsets.UTF_8));
		for (String value : intent.requestedGroups().stream().sorted().toList()) digest.update(("group=" + value + "\n").getBytes(StandardCharsets.UTF_8));
		for (String value : intent.requestedCategories().stream().sorted().toList()) digest.update(("category=" + value + "\n").getBytes(StandardCharsets.UTF_8));
		for (String value : intent.excludedGroups().stream().sorted().toList()) digest.update(("excluded=" + value + "\n").getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest.digest());
	}

	private static ClientConfigJsons.ClientConfigFieldsV3 copyConfig(ClientConfigJsons.ClientConfigFieldsV3 config) {
		return new ClientConfigJsons.ClientConfigFieldsV3(Objects.requireNonNull(config, "expectedClientConfig"));
	}

	public enum Phase {
		PLANNED,
		PREPARING,
		PROJECTED,
		SWAPPING,
		COMMITTED,
		DEFERRED
	}

	public enum Purpose {
		MODPACK_UPDATE,
		MODPACK_DEACTIVATION,
		MODPACK_REMOVAL,
		SELF_UPDATE
	}

	public enum Status {
		SUCCESS,
		DEFERRED_LOCKED,
		REPLAN_REQUIRED,
		FAILED
	}
}
