package pl.skidam.automodpack_core.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.BaselineCapture;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.LegacyDummyFiles;

/** The single write-ahead record for one client update. It stores intent and operations, never filesystem paths or duplicated manifests. */
public final class UpdateTransaction {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public int schemaVersion;
	public String transactionId;
	public Purpose purpose;
	public Phase phase;
	public String modpackId;
	public String targetGenerationId;
	public String parentGenerationId;
	public String stateDigest;
	public String ledgerDigest;
	public String targetPlatform;
	public String selectionDigest;
	public String overlayDigest;
	public boolean expectedPriorSelectionPresent;
	public List<String> expectedPriorRequestedTags;
	public List<String> expectedPriorRequestedGroups;
	public List<String> expectedPriorExcludedGroups;
	public List<String> requestedTags;
	public List<String> requestedGroups;
	public List<String> excludedGroups;
	public List<Operation> operations;
	public List<ProjectedFile> projectedFinalState;
	public Jsons.ClientConfigFieldsV3 plannedClientConfig;
	public List<RestartReason> restartReasons;
	public List<Preservation> plannedPreservations;
	public List<BaselineCapture> plannedBaselineCaptures;
	public Status resultStatus;
	public String resultOperation;
	public String resultPath;
	public String resultMessage;

	public UpdateTransaction() {}

	public static UpdateTransaction create(UpdatePlan plan, SelectedModpackTarget target, String overlayDigest) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(target, "target");
		if (!plan.modpackId().equals(target.manifest().modpackId())) throw new IllegalArgumentException("Plan and selected target modpack IDs disagree");
		if (!plan.generationTarget().equals(target.generationTarget())) throw new IllegalArgumentException("Plan and selected target generation identities disagree");
		if (!plan.generationTarget().equals(GenerationTarget.fromFlat(target.flatTarget())))
			throw new IllegalArgumentException("Plan and selected flat target generation identities disagree");

		UpdateTransaction transaction = base(Purpose.MODPACK_UPDATE);
		fillGeneration(transaction, plan.generationTarget());
		transaction.targetPlatform = target.platform().id();
		transaction.expectedPriorSelectionPresent = target.expectedPriorIntent() != null;
		transaction.expectedPriorRequestedTags = intentValues(target.expectedPriorIntent(), IntentPart.TAGS);
		transaction.expectedPriorRequestedGroups = intentValues(target.expectedPriorIntent(), IntentPart.GROUPS);
		transaction.expectedPriorExcludedGroups = intentValues(target.expectedPriorIntent(), IntentPart.EXCLUDED);
		transaction.requestedTags = new ArrayList<>(target.selection().intent().requestedTags());
		transaction.requestedGroups = new ArrayList<>(target.selection().intent().requestedGroups());
		transaction.excludedGroups = new ArrayList<>(target.selection().intent().excludedGroups());
		transaction.selectionDigest = digest(target.selection().intent());
		transaction.overlayDigest = overlayDigest == null ? "" : overlayDigest;
		fillPlan(transaction, plan);
		return transaction;
	}

	public static UpdateTransaction createRemoval(UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent, String overlayDigest) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(platform, "platform");
		UpdateTransaction transaction = base(Purpose.MODPACK_REMOVAL);
		fillGeneration(transaction, plan.generationTarget());
		transaction.targetPlatform = platform.id();
		transaction.expectedPriorSelectionPresent = expectedPriorIntent != null;
		transaction.expectedPriorRequestedTags = intentValues(expectedPriorIntent, IntentPart.TAGS);
		transaction.expectedPriorRequestedGroups = intentValues(expectedPriorIntent, IntentPart.GROUPS);
		transaction.expectedPriorExcludedGroups = intentValues(expectedPriorIntent, IntentPart.EXCLUDED);
		transaction.requestedTags = List.of();
		transaction.requestedGroups = List.of();
		transaction.excludedGroups = List.of();
		transaction.selectionDigest = digest(expectedPriorIntent);
		transaction.overlayDigest = overlayDigest == null ? "" : overlayDigest;
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

	public static UpdateTransaction createLegacyDummyCleanup(List<LegacyDummyTarget> targets) {
		UpdateTransaction transaction = base(Purpose.LEGACY_DUMMY_CLEANUP);
		transaction.operations = targets.stream().map(target -> new Operation(target.root(), target.relativePath(), OperationType.DELETE, null, -1, LegacyDummyFiles.SHA1))
				.sorted(Comparator.comparing((Operation operation) -> operation.operation().ordinal()).thenComparing(operation -> operation.root().ordinal())
						.thenComparing(Operation::relativePath))
				.toList();
		transaction.projectedFinalState = targets.stream().map(target -> new ProjectedFile(target.root(), target.relativePath(), false, null, -1))
				.sorted(Comparator.comparing((ProjectedFile projected) -> projected.root().ordinal()).thenComparing(ProjectedFile::relativePath)).toList();
		transaction.restartReasons = List.of();
		return transaction;
	}

	private static void fillGeneration(UpdateTransaction transaction, GenerationTarget target) {
		transaction.modpackId = target.modpackId();
		transaction.targetGenerationId = target.targetGenerationId();
		transaction.parentGenerationId = target.parentGenerationId();
		transaction.stateDigest = target.stateDigest();
		transaction.ledgerDigest = target.ledgerDigest();
	}

	private static void fillPlan(UpdateTransaction transaction, UpdatePlan plan) {
		transaction.operations = List.copyOf(plan.operations());
		transaction.projectedFinalState = List.copyOf(plan.projectedFinalState());
		transaction.plannedClientConfig = plan.plannedClientConfig();
		transaction.restartReasons = new ArrayList<>(new LinkedHashSet<>(plan.restartReasons()));
		transaction.plannedPreservations = List.copyOf(plan.preservations());
		transaction.plannedBaselineCaptures = List.copyOf(plan.baselineCaptures());
	}

	private static UpdateTransaction base(Purpose purpose) {
		UpdateTransaction transaction = new UpdateTransaction();
		transaction.schemaVersion = CURRENT_SCHEMA_VERSION;
		transaction.transactionId = UUID.randomUUID().toString();
		transaction.purpose = purpose;
		transaction.phase = Phase.PLANNED;
		transaction.plannedPreservations = new ArrayList<>();
		transaction.plannedBaselineCaptures = new ArrayList<>();
		return transaction;
	}

	private static void sortOperations(List<Operation> operations) {
		operations.sort(Comparator.comparing((Operation operation) -> operation.operation().ordinal()).thenComparing(operation -> operation.root().ordinal())
				.thenComparing(Operation::relativePath));
	}

	private enum IntentPart {
		TAGS, GROUPS, EXCLUDED
	}

	private static List<String> intentValues(SelectionIntent intent, IntentPart part) {
		if (intent == null) return List.of();
		return switch (part) {
			case TAGS -> new ArrayList<>(intent.requestedTags());
			case GROUPS -> new ArrayList<>(intent.requestedGroups());
			case EXCLUDED -> new ArrayList<>(intent.excludedGroups());
		};
	}

	public GenerationTarget generationTarget() {
		return new GenerationTarget(modpackId, targetGenerationId, parentGenerationId, stateDigest, ledgerDigest);
	}

	public ClientPlatform platform() {
		return ClientPlatform.parse(targetPlatform);
	}

	public SelectionIntent expectedPriorIntent() {
		return expectedPriorSelectionPresent ? new SelectionIntent(expectedPriorRequestedTags, expectedPriorRequestedGroups, expectedPriorExcludedGroups) : null;
	}

	public SelectionIntent targetIntent() {
		return new SelectionIntent(requestedTags, requestedGroups, excludedGroups);
	}

	public static String digest(SelectionIntent intent) {
		if (intent == null) return "";
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			digest.update("automodpack-selection-v1\n".getBytes(StandardCharsets.UTF_8));
			for (String value : intent.requestedTags().stream().sorted().toList()) digest.update(("tag=" + value + "\n").getBytes(StandardCharsets.UTF_8));
			for (String value : intent.requestedGroups().stream().sorted().toList()) digest.update(("group=" + value + "\n").getBytes(StandardCharsets.UTF_8));
			for (String value : intent.excludedGroups().stream().sorted().toList()) digest.update(("excluded=" + value + "\n").getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-1 is required by the client protocol", e);
		}
	}

	public record LegacyDummyTarget(Root root, String relativePath) {}

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
		MODPACK_REMOVAL,
		SELF_UPDATE,
		LEGACY_DUMMY_CLEANUP
	}

	public enum Status {
		SUCCESS,
		DEFERRED_LOCKED,
		FAILED
	}
}
