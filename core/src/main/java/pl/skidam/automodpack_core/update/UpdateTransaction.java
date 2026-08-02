package pl.skidam.automodpack_core.update;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.LegacyDummyFiles;

public final class UpdateTransaction {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public int schemaVersion;
	public String transactionId;
	public Purpose purpose;
	public String modpackId;
	public String targetGenerationId;
	public String parentGenerationId;
	public String stateDigest;
	public String completeManifestJson;
	public String targetManifestJson;
	public String targetPlatform;
	public boolean expectedPriorSelectionPresent;
	public List<String> expectedPriorRequestedGroups;
	public List<String> requestedGroups;
	public String canonicalModpackDirectory;
	public List<Operation> operations;
	public List<ProjectedFile> projectedFinalState;
	public Jsons.ClientConfigFieldsV3 plannedClientConfig;
	public List<RestartReason> restartReasons;

	public UpdateTransaction() {}

	public static UpdateTransaction create(UpdatePlan plan, SelectedModpackTarget target, Path modpackDirectory) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(target, "target");
		if (!plan.modpackId().equals(target.manifest().modpackId())) throw new IllegalArgumentException("Plan and selected target modpack IDs disagree");
		if (!plan.generationTarget().equals(target.generationTarget())) throw new IllegalArgumentException("Plan and selected target generation identities disagree");
		if (!plan.generationTarget().equals(GenerationTarget.fromFlat(target.flatTarget())))
			throw new IllegalArgumentException("Plan and selected flat target generation identities disagree");

		UpdateTransaction transaction = base(Purpose.MODPACK_UPDATE);
		transaction.modpackId = plan.modpackId();
		transaction.targetGenerationId = plan.generationTarget().targetGenerationId();
		transaction.parentGenerationId = plan.generationTarget().parentGenerationId();
		transaction.stateDigest = plan.generationTarget().stateDigest();
		transaction.completeManifestJson = ConfigTools.GSON.toJson(target.completeFields());
		transaction.targetManifestJson = ConfigTools.GSON.toJson(target.flatTarget());
		transaction.targetPlatform = target.platform().id();
		transaction.expectedPriorSelectionPresent = target.expectedPriorIntent() != null;
		transaction.expectedPriorRequestedGroups = target.expectedPriorIntent() == null ? List.of() : new ArrayList<>(target.expectedPriorIntent().requestedGroups());
		transaction.requestedGroups = new ArrayList<>(target.selection().intent().requestedGroups());
		transaction.canonicalModpackDirectory = modpackDirectory.toAbsolutePath().normalize().toString();
		transaction.operations = List.copyOf(plan.operations());
		transaction.projectedFinalState = List.copyOf(plan.projectedFinalState());
		transaction.plannedClientConfig = plan.plannedClientConfig();
		transaction.restartReasons = new ArrayList<>(new LinkedHashSet<>(plan.restartReasons()));
		return transaction;
	}

	public static UpdateTransaction createSelfUpdate(String currentJar, String targetJar, String targetHash, long targetSize, String currentHash) {
		UpdateTransaction transaction = base(Purpose.SELF_UPDATE);
		List<Operation> operations = new ArrayList<>();
		operations.add(new Operation(Root.MODS_DIR, targetJar, OperationType.INSTALL_OBJECT, targetHash, targetSize, null));
		List<ProjectedFile> finalState = new ArrayList<>();
		finalState.add(new ProjectedFile(Root.MODS_DIR, targetJar, true, targetHash, targetSize));
		if (!currentJar.equals(targetJar)) {
			operations.add(new Operation(Root.MODS_DIR, currentJar, OperationType.DELETE, null, -1, currentHash));
			finalState.add(new ProjectedFile(Root.MODS_DIR, currentJar, false, null, -1));
		}
		operations.sort(Comparator.comparing((Operation operation) -> operation.operation().ordinal()).thenComparing(operation -> operation.root().ordinal())
				.thenComparing(Operation::relativePath));
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

	private static UpdateTransaction base(Purpose purpose) {
		UpdateTransaction transaction = new UpdateTransaction();
		transaction.schemaVersion = CURRENT_SCHEMA_VERSION;
		transaction.transactionId = UUID.randomUUID().toString();
		transaction.purpose = purpose;
		return transaction;
	}

	public Jsons.ModpackContentFields targetManifest() {
		return ConfigTools.parse(targetManifestJson, Jsons.ModpackContentFields.class);
	}

	public GenerationRecord completeGenerationRecord() {
		return GenerationRecord.fromFields(ConfigTools.parse(completeManifestJson, Jsons.CompleteModpackContentFields.class));
	}

	public GenerationTarget generationTarget() {
		return new GenerationTarget(targetGenerationId, parentGenerationId, stateDigest);
	}

	public ClientPlatform platform() {
		return ClientPlatform.parse(targetPlatform);
	}

	public SelectionIntent expectedPriorIntent() {
		return expectedPriorSelectionPresent ? new SelectionIntent(expectedPriorRequestedGroups) : null;
	}

	public SelectionIntent targetIntent() {
		return new SelectionIntent(requestedGroups);
	}

	public record LegacyDummyTarget(Root root, String relativePath) {}

	public enum Purpose {
		MODPACK_UPDATE,
		SELF_UPDATE,
		LEGACY_DUMMY_CLEANUP
	}
}
