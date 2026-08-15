package pl.skidam.automodpack_core.update;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.annotations.JsonAdapter;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Immutable prepared reconciliation decision: executable intent and its canonical user-visible consequences. */
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
		List<NestedCopy> generatedCopies,
		ChangeSet consequences) {

	public UpdatePlan {
		generationTarget = Objects.requireNonNull(generationTarget, "generationTarget");
		operations = List.copyOf(operations);
		projectedFinalState = List.copyOf(projectedFinalState);
		restartReasons = stableSet(restartReasons);
		preservations = List.copyOf(preservations);
		baselineCaptures = List.copyOf(baselineCaptures);
		conflicts = List.copyOf(conflicts);
		generatedCopies = List.copyOf(generatedCopies);
		consequences = Objects.requireNonNull(consequences, "reconciliation consequences");
	}

	public UpdatePlan withRestartReason(RestartReason reason) {
		LinkedHashSet<RestartReason> reasons = new LinkedHashSet<>(restartReasons);
		if (!reasons.add(Objects.requireNonNull(reason, "restart reason"))) return this;
		return new UpdatePlan(modpackId, generationTarget, operations, projectedFinalState, plannedClientConfig, reasons, preservations, baselineCaptures, conflicts,
				generatedCopies, consequences.withEffects(List.of(new ChangeSet.Effect("restart", reason.name()))));
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
		REMOVED_LOCAL_MODS,
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
		SERVER_LEDGER,
		PLAYER_CONSENT
	}

	@JsonAdapter(UpdatePlanJsonAdapters.PreservationAdapter.class)
	public record Preservation(Root root, String relativePath, String expectedHash, long expectedSize, PreservationProof proof) {
		public Preservation(Root root, String relativePath, String expectedHash, long expectedSize) {
			this(root, relativePath, expectedHash, expectedSize, PreservationProof.ACTIVE_LEDGER);
		}
	}

	@JsonAdapter(UpdatePlanJsonAdapters.BaselineCaptureAdapter.class)
	public record BaselineCapture(Root root, String relativePath, String expectedHash, long expectedSize, boolean absent) {}

	public enum ConflictAction {
		PRESERVE_LOCAL,
		REMOVE_OWNED
	}

	@JsonAdapter(UpdatePlanJsonAdapters.ConflictAdapter.class)
	public record Conflict(String modpackId, String conflictId, Set<String> modIds, String sourcePath, String sourceHash, long sourceSize,
			String targetPath, String targetHash, long targetSize, ConflictAction action) {
		public Conflict {
			ModpackId.requireValid(modpackId);
			if (!HashUtils.isCanonicalSha1(conflictId)) throw new IllegalArgumentException("Invalid conflict ID");
			TreeSet<String> normalizedIds = new TreeSet<>();
			if (modIds != null) for (String modId : modIds) {
				if (modId == null || modId.isBlank()) throw new IllegalArgumentException("Conflict mod ID is missing");
				normalizedIds.add(modId.toLowerCase(Locale.ROOT));
			}
			if (normalizedIds.isEmpty()) throw new IllegalArgumentException("Conflict has no mod IDs");
			modIds = Collections.unmodifiableSet(new LinkedHashSet<>(normalizedIds));
			sourcePath = LogicalPath.requireCanonical(sourcePath);
			targetPath = LogicalPath.requireCanonical(targetPath);
			if (!HashUtils.isSha1(sourceHash) || sourceSize < 0)
				throw new IllegalArgumentException("Conflict source content is invalid");
			if (!HashUtils.isSha1(targetHash) || targetSize < 0)
				throw new IllegalArgumentException("Conflict target content is invalid");
			action = Objects.requireNonNull(action, "conflict action");
		}
	}

	@JsonAdapter(UpdatePlanJsonAdapters.OperationAdapter.class)
	public record Operation(
			Root root,
			String relativePath,
			OperationType operation,
			String expectedObjectHash,
			long expectedSize,
			String expectedExistingHash) {}

	@JsonAdapter(UpdatePlanJsonAdapters.ProjectedFileAdapter.class)
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
			if (!HashUtils.isSha1(sha1)) throw new IllegalArgumentException("Nested-copy SHA-1 is invalid");
			sha1 = HashUtils.normalizeSha1(sha1);
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
