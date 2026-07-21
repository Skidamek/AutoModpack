package pl.skidam.automodpack_core.update;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import pl.skidam.automodpack_core.config.Jsons;

public record UpdatePlan(
		String modpackId,
		List<Operation> operations,
		List<ProjectedFile> projectedFinalState,
		Jsons.ClientConfigFieldsV3 plannedClientConfig,
		Set<String> plannedDeletionTimestamps,
		Set<RestartReason> restartReasons,
		List<Warning> warnings) {

	public UpdatePlan {
		operations = List.copyOf(operations);
		projectedFinalState = List.copyOf(projectedFinalState);
		plannedDeletionTimestamps = stableSet(plannedDeletionTimestamps);
		restartReasons = stableSet(restartReasons);
		warnings = List.copyOf(warnings);
	}

	private static <T> Set<T> stableSet(Set<T> values) {
		return Collections.unmodifiableSet(new LinkedHashSet<>(values));
	}

	public enum Root {
		MODPACK_DIR,
		GAME_DIR,
		MODS_DIR,
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
		CHANGED_LOADER_VERSION
	}

	public enum WarningType {
		REMOTE_DELETION_DISABLED,
		REMOTE_DELETION_HASH_MISMATCH
	}

	public record Warning(WarningType type, String timestamp, String requestedPath, String expectedHash, String actualPath, String actualHash) {}

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

	public record NestedCopy(String targetFileName, String sha1, long size, Set<String> ids) {
		public NestedCopy {
			ids = stableSet(ids);
		}
	}
}
