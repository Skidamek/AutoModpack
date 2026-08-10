package pl.skidam.automodpack_core.update;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;

public record UpdatePlan(
		String modpackId,
		GenerationTarget generationTarget,
		List<Operation> operations,
		List<ProjectedFile> projectedFinalState,
		ClientConfigJsons.ClientConfigFieldsV3 plannedClientConfig,
		Set<RestartReason> restartReasons,
		List<Preservation> preservations,
		List<BaselineCapture> baselineCaptures,
		List<Conflict> conflicts,
		List<NestedCopy> generatedCopies) {

	public UpdatePlan {
		generationTarget = Objects.requireNonNull(generationTarget, "generationTarget");
		operations = List.copyOf(operations);
		projectedFinalState = List.copyOf(projectedFinalState);
		restartReasons = stableSet(restartReasons);
		preservations = List.copyOf(preservations);
		baselineCaptures = List.copyOf(baselineCaptures);
		conflicts = List.copyOf(conflicts);
		generatedCopies = List.copyOf(generatedCopies);
	}

	private static <T> Set<T> stableSet(Set<T> values) {
		return Collections.unmodifiableSet(new LinkedHashSet<>(values));
	}

	public enum Root {
		PROJECTION,
		OVERLAY,
		GAME_DIR
	}

	public enum OperationType {
		INSTALL_OBJECT,
		DELETE
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

	public enum PreservationProof {
		ACTIVE_LEDGER,
		SERVER_LEDGER
	}

	public record Preservation(Root root, String relativePath, String expectedHash, long expectedSize, PreservationProof proof) {
		public Preservation(Root root, String relativePath, String expectedHash, long expectedSize) {
			this(root, relativePath, expectedHash, expectedSize, PreservationProof.ACTIVE_LEDGER);
		}
	}

	public record BaselineCapture(Root root, String relativePath, String expectedHash, long expectedSize, boolean absent) {}

	public enum ConflictAction {
		QUARANTINE,
		REMOVE_OWNED
	}

	public record Conflict(String modpackId, String conflictId, Set<String> modIds, String sourcePath, String sourceHash, long sourceSize,
			String targetPath, String targetHash, long targetSize, ConflictAction action) {
		public Conflict {
			ModpackId.requireValid(modpackId);
			if (conflictId == null || !conflictId.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid conflict ID");
			TreeSet<String> normalizedIds = new TreeSet<>();
			if (modIds != null) for (String modId : modIds) {
				if (modId == null || modId.isBlank()) throw new IllegalArgumentException("Conflict mod ID is missing");
				normalizedIds.add(modId.toLowerCase(Locale.ROOT));
			}
			if (normalizedIds.isEmpty()) throw new IllegalArgumentException("Conflict has no mod IDs");
			modIds = Collections.unmodifiableSet(new LinkedHashSet<>(normalizedIds));
			sourcePath = LogicalPath.requireCanonical(sourcePath);
			targetPath = LogicalPath.requireCanonical(targetPath);
			if (sourceHash == null || !sourceHash.matches("[0-9a-fA-F]{40}") || sourceSize < 0)
				throw new IllegalArgumentException("Conflict source content is invalid");
			if (targetHash == null || !targetHash.matches("[0-9a-fA-F]{40}") || targetSize < 0)
				throw new IllegalArgumentException("Conflict target content is invalid");
			action = Objects.requireNonNull(action, "conflict action");
		}
	}

	public record Operation(
			Root root,
			String relativePath,
			OperationType operation,
			String expectedObjectHash,
			long expectedSize,
			String expectedExistingHash) {}

	public record ProjectedFile(Root root, String relativePath, boolean present, String expectedHash, long expectedSize) {}

	public record FileKey(Root root, String relativePath) {}

	public record FileState(String sha1, long size, boolean regularFile) {}

	public record ModInfo(String relativePath, String sha1, long size, Set<String> ids, Set<String> dependencies) {
		public ModInfo {
			ids = normalizedSet(ids);
			dependencies = normalizedSet(dependencies);
		}
	}

	public record NestedCopy(String relativePath, String sha1, long size, Set<String> ids) {
		public NestedCopy {
			relativePath = LogicalPath.requireCanonical(relativePath);
			if (sha1 == null || !sha1.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("Nested-copy SHA-1 is invalid");
			sha1 = sha1.toLowerCase(Locale.ROOT);
			if (size < 0) throw new IllegalArgumentException("Nested-copy size is invalid");
			ids = stableSet(ids);
		}
	}

	private static Set<String> normalizedSet(Set<String> values) {
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		if (values != null) for (String value : values) if (value != null && !value.isBlank()) normalized.add(value.toLowerCase(Locale.ROOT));
		return Collections.unmodifiableSet(normalized);
	}
}
