package pl.skidam.automodpack_core.update;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;

public record UpdatePlan(
		String modpackId,
		GenerationTarget generationTarget,
		List<Operation> operations,
		List<ProjectedFile> projectedFinalState,
		Jsons.ClientConfigFieldsV3 plannedClientConfig,
		Set<RestartReason> restartReasons,
		List<Preservation> preservations,
		List<BaselineCapture> baselineCaptures) {

	public UpdatePlan {
		generationTarget = Objects.requireNonNull(generationTarget, "generationTarget");
		operations = List.copyOf(operations);
		projectedFinalState = List.copyOf(projectedFinalState);
		restartReasons = stableSet(restartReasons);
		preservations = List.copyOf(preservations);
		baselineCaptures = List.copyOf(baselineCaptures);
	}

	public UpdatePlan(String modpackId, GenerationTarget generationTarget, List<Operation> operations, List<ProjectedFile> projectedFinalState,
			Jsons.ClientConfigFieldsV3 plannedClientConfig, Set<RestartReason> restartReasons) {
		this(modpackId, generationTarget, operations, projectedFinalState, plannedClientConfig, restartReasons, List.of(), List.of());
	}

	public UpdatePlan(String modpackId, GenerationTarget generationTarget, List<Operation> operations, List<ProjectedFile> projectedFinalState,
			Jsons.ClientConfigFieldsV3 plannedClientConfig, Set<RestartReason> restartReasons, List<Preservation> preservations) {
		this(modpackId, generationTarget, operations, projectedFinalState, plannedClientConfig, restartReasons, preservations, List.of());
	}

	private static <T> Set<T> stableSet(Set<T> values) {
		return Collections.unmodifiableSet(new LinkedHashSet<>(values));
	}

	public enum Root {
		PROJECTION,
		OVERLAY,
		GAME_DIR,
		STORE_DIR,
		AUTOMODPACK_DIR
	}

	public enum OperationType {
		CREATE_DIRECTORY,
		INSTALL_OBJECT,
		DELETE,
		REMOVE_EMPTY_DIRECTORY
	}

	public enum RestartReason {
		REMOVED_NON_MODPACK_FILES,
		CORRECTED_FILE_LOCATIONS,
		FIXED_NESTED_MODS,
		REMOVED_DUPLICATE_MODS,
		REMOVED_STANDARD_MODS,
		APPLIED_SERVER_DELETIONS,
		CHANGED_LOADER_VERSION,
		CHANGED_GROUP_SELECTION,
		SELECTED_MODPACK
	}

	public record Preservation(Root root, String relativePath, String expectedHash, long expectedSize) {}

	public record BaselineCapture(Root root, String relativePath, String expectedHash, long expectedSize, boolean absent) {}

	public record Operation(
			Root root,
			String relativePath,
			OperationType operation,
			String expectedObjectHash,
			long expectedSize,
			String expectedExistingHash) {}

	public record ProjectedFile(Root root, String relativePath, boolean present, String expectedHash, long expectedSize) {}

	public record FileKey(Root root, String relativePath) {}

	public record FileState(String sha1, long size, boolean regularFile, boolean mod) {}

	public record ModInfo(String relativePath, String sha1, long size, Set<String> ids, Set<String> dependencies) {
		public ModInfo {
			ids = stableSet(ids);
			dependencies = stableSet(dependencies);
		}
	}

	public record NestedCopy(String relativePath, String sha1, long size, Set<String> ids) {
		public NestedCopy {
			relativePath = LogicalPath.normalize(relativePath);
			ids = stableSet(ids);
		}
	}
}
