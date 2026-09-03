package pl.skidam.automodpack_core.update;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Immutable prepared reconciliation decision: executable intent and its canonical user-visible consequences. */
public record UpdatePlan(
		String modpackId,
		PackTarget packTarget,
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
		packTarget = Objects.requireNonNull(packTarget, "packTarget");
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
		return new UpdatePlan(modpackId, packTarget, operations, projectedFinalState, plannedClientConfig, reasons, preservations, baselineCaptures, conflicts,
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

	/* These entries are persisted in UpdateTransaction; old Minecraft Gson can read classes but not record fields. */
	public static final class Preservation {
		private Root root;
		private String relativePath;
		private String expectedHash;
		private long expectedSize;
		private PreservationProof proof;

		public Preservation() {}

		public Preservation(Root root, String relativePath, String expectedHash, long expectedSize, PreservationProof proof) {
			this.root = root;
			this.relativePath = relativePath;
			this.expectedHash = expectedHash;
			this.expectedSize = expectedSize;
			this.proof = proof;
		}

		public Preservation(Root root, String relativePath, String expectedHash, long expectedSize) {
			this(root, relativePath, expectedHash, expectedSize, PreservationProof.ACTIVE_LEDGER);
		}

		public Root root() {
			return root;
		}

		public String relativePath() {
			return relativePath;
		}

		public String expectedHash() {
			return expectedHash;
		}

		public long expectedSize() {
			return expectedSize;
		}

		public PreservationProof proof() {
			return proof;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof Preservation other)) return false;
			return expectedSize == other.expectedSize && root == other.root && Objects.equals(relativePath, other.relativePath)
					&& Objects.equals(expectedHash, other.expectedHash) && proof == other.proof;
		}

		@Override
		public int hashCode() {
			return Objects.hash(root, relativePath, expectedHash, expectedSize, proof);
		}

		@Override
		public String toString() {
			return "Preservation[root=" + root + ", relativePath=" + relativePath + ", expectedHash=" + expectedHash + ", expectedSize=" + expectedSize + ", proof=" + proof + "]";
		}
	}

	public static final class BaselineCapture {
		private Root root;
		private String relativePath;
		private String expectedHash;
		private long expectedSize;
		private boolean absent;

		public BaselineCapture() {}

		public BaselineCapture(Root root, String relativePath, String expectedHash, long expectedSize, boolean absent) {
			this.root = root;
			this.relativePath = relativePath;
			this.expectedHash = expectedHash;
			this.expectedSize = expectedSize;
			this.absent = absent;
		}

		public Root root() {
			return root;
		}

		public String relativePath() {
			return relativePath;
		}

		public String expectedHash() {
			return expectedHash;
		}

		public long expectedSize() {
			return expectedSize;
		}

		public boolean absent() {
			return absent;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof BaselineCapture other)) return false;
			return expectedSize == other.expectedSize && absent == other.absent && root == other.root && Objects.equals(relativePath, other.relativePath)
					&& Objects.equals(expectedHash, other.expectedHash);
		}

		@Override
		public int hashCode() {
			return Objects.hash(root, relativePath, expectedHash, expectedSize, absent);
		}

		@Override
		public String toString() {
			return "BaselineCapture[root=" + root + ", relativePath=" + relativePath + ", expectedHash=" + expectedHash + ", expectedSize=" + expectedSize + ", absent=" + absent + "]";
		}
	}

	public enum ConflictAction {
		PRESERVE_LOCAL,
		REMOVE_OWNED
	}

	public static final class Conflict {
		private String modpackId;
		private String conflictId;
		private Set<String> modIds;
		private String sourcePath;
		private String sourceHash;
		private long sourceSize;
		private String targetPath;
		private String targetHash;
		private long targetSize;
		private ConflictAction action;

		public Conflict() {}

		public Conflict(String modpackId, String conflictId, Set<String> modIds, String sourcePath, String sourceHash, long sourceSize,
				String targetPath, String targetHash, long targetSize, ConflictAction action) {
			this.modpackId = modpackId;
			this.conflictId = conflictId;
			this.modIds = modIds;
			this.sourcePath = sourcePath;
			this.sourceHash = sourceHash;
			this.sourceSize = sourceSize;
			this.targetPath = targetPath;
			this.targetHash = targetHash;
			this.targetSize = targetSize;
			this.action = action;
			validate();
		}

		public void validate() {
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

		public String modpackId() {
			return modpackId;
		}

		public String conflictId() {
			return conflictId;
		}

		public Set<String> modIds() {
			return modIds;
		}

		public String sourcePath() {
			return sourcePath;
		}

		public String sourceHash() {
			return sourceHash;
		}

		public long sourceSize() {
			return sourceSize;
		}

		public String targetPath() {
			return targetPath;
		}

		public String targetHash() {
			return targetHash;
		}

		public long targetSize() {
			return targetSize;
		}

		public ConflictAction action() {
			return action;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof Conflict other)) return false;
			return sourceSize == other.sourceSize && targetSize == other.targetSize && Objects.equals(modpackId, other.modpackId)
					&& Objects.equals(conflictId, other.conflictId) && Objects.equals(modIds, other.modIds) && Objects.equals(sourcePath, other.sourcePath)
					&& Objects.equals(sourceHash, other.sourceHash) && Objects.equals(targetPath, other.targetPath) && Objects.equals(targetHash, other.targetHash)
					&& action == other.action;
		}

		@Override
		public int hashCode() {
			return Objects.hash(modpackId, conflictId, modIds, sourcePath, sourceHash, sourceSize, targetPath, targetHash, targetSize, action);
		}

		@Override
		public String toString() {
			return "Conflict[modpackId=" + modpackId + ", conflictId=" + conflictId + ", modIds=" + modIds + ", sourcePath=" + sourcePath + ", sourceHash=" + sourceHash
					+ ", sourceSize=" + sourceSize + ", targetPath=" + targetPath + ", targetHash=" + targetHash + ", targetSize=" + targetSize + ", action=" + action + "]";
		}
	}

	public static final class Operation {
		/** The canonical deterministic execution order shared by the planner, the transaction, and the executor. */
		public static final Comparator<Operation> ORDER = Comparator.comparing((Operation operation) -> operation.operation().ordinal())
				.thenComparing(operation -> operation.root().ordinal()).thenComparing(Operation::relativePath);
		private Root root;
		private String relativePath;
		private OperationType operation;
		private String expectedObjectHash;
		private long expectedSize;
		private String expectedExistingHash;

		public Operation() {}

		public Operation(Root root, String relativePath, OperationType operation, String expectedObjectHash, long expectedSize, String expectedExistingHash) {
			this.root = root;
			this.relativePath = relativePath;
			this.operation = operation;
			this.expectedObjectHash = expectedObjectHash;
			this.expectedSize = expectedSize;
			this.expectedExistingHash = expectedExistingHash;
		}

		public Root root() {
			return root;
		}

		public String relativePath() {
			return relativePath;
		}

		public OperationType operation() {
			return operation;
		}

		public String expectedObjectHash() {
			return expectedObjectHash;
		}

		public long expectedSize() {
			return expectedSize;
		}

		public String expectedExistingHash() {
			return expectedExistingHash;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof Operation other)) return false;
			return expectedSize == other.expectedSize && root == other.root && Objects.equals(relativePath, other.relativePath) && operation == other.operation
					&& Objects.equals(expectedObjectHash, other.expectedObjectHash) && Objects.equals(expectedExistingHash, other.expectedExistingHash);
		}

		@Override
		public int hashCode() {
			return Objects.hash(root, relativePath, operation, expectedObjectHash, expectedSize, expectedExistingHash);
		}

		@Override
		public String toString() {
			return "Operation[root=" + root + ", relativePath=" + relativePath + ", operation=" + operation + ", expectedObjectHash=" + expectedObjectHash + ", expectedSize="
					+ expectedSize + ", expectedExistingHash=" + expectedExistingHash + "]";
		}
	}

	public static final class ProjectedFile {
		private Root root;
		private String relativePath;
		private boolean present;
		private String expectedHash;
		private long expectedSize;

		public ProjectedFile() {}

		public ProjectedFile(Root root, String relativePath, boolean present, String expectedHash, long expectedSize) {
			this.root = root;
			this.relativePath = relativePath;
			this.present = present;
			this.expectedHash = expectedHash;
			this.expectedSize = expectedSize;
		}

		public Root root() {
			return root;
		}

		public String relativePath() {
			return relativePath;
		}

		public boolean present() {
			return present;
		}

		public String expectedHash() {
			return expectedHash;
		}

		public long expectedSize() {
			return expectedSize;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof ProjectedFile other)) return false;
			return present == other.present && expectedSize == other.expectedSize && root == other.root && Objects.equals(relativePath, other.relativePath)
					&& Objects.equals(expectedHash, other.expectedHash);
		}

		@Override
		public int hashCode() {
			return Objects.hash(root, relativePath, present, expectedHash, expectedSize);
		}

		@Override
		public String toString() {
			return "ProjectedFile[root=" + root + ", relativePath=" + relativePath + ", present=" + present + ", expectedHash=" + expectedHash + ", expectedSize=" + expectedSize + "]";
		}
	}

	public record FileKey(Root root, String relativePath) {
		public static final Comparator<FileKey> ORDER = Comparator.comparing((FileKey key) -> key.root().ordinal()).thenComparing(FileKey::relativePath);
	}

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
