package pl.skidam.automodpack_core.update;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
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
	public String targetManifestJson;
	public String canonicalModpackDirectory;
	public List<Operation> operations;
	public List<ProjectedFile> projectedFinalState;
	public Jsons.ClientConfigFieldsV3 plannedClientConfig;
	public List<String> plannedDeletionTimestamps;
	public List<RestartReason> restartReasons;

	public UpdateTransaction() {}

	public static UpdateTransaction create(UpdatePlan plan, Jsons.ModpackContentFields targetManifest, Path modpackDirectory) {
		UpdateTransaction transaction = base(Purpose.MODPACK_UPDATE);
		transaction.modpackId = plan.modpackId();
		transaction.targetManifestJson = ConfigTools.GSON.toJson(targetManifest);
		transaction.canonicalModpackDirectory = modpackDirectory.toAbsolutePath().normalize().toString();
		transaction.operations = List.copyOf(plan.operations());
		transaction.projectedFinalState = List.copyOf(plan.projectedFinalState());
		transaction.plannedClientConfig = plan.plannedClientConfig();
		transaction.plannedDeletionTimestamps = new ArrayList<>(plan.plannedDeletionTimestamps());
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
		transaction.plannedDeletionTimestamps = List.of();
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
		transaction.plannedDeletionTimestamps = List.of();
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

	public record LegacyDummyTarget(Root root, String relativePath) {}

	public enum Purpose {
		MODPACK_UPDATE,
		SELF_UPDATE,
		LEGACY_DUMMY_CLEANUP
	}
}
