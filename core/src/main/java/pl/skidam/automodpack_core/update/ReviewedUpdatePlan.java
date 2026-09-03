package pl.skidam.automodpack_core.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.BaselineCapture;
import pl.skidam.automodpack_core.update.UpdatePlan.Conflict;
import pl.skidam.automodpack_core.update.UpdatePlan.NestedCopy;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.Preservation;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Owns the finite lifecycle and execution fingerprint of one player-reviewed update plan. */
public final class ReviewedUpdatePlan {
	private final UpdatePlan plan;
	private final String executionDigest;
	private State state;

	private ReviewedUpdatePlan(UpdatePlan plan, State state) {
		this.plan = Objects.requireNonNull(plan, "update plan");
		this.executionDigest = executionDigest(plan);
		this.state = Objects.requireNonNull(state, "review state");
	}

	public static ReviewedUpdatePlan pending(UpdatePlan plan) {
		return new ReviewedUpdatePlan(plan, State.PENDING_REVIEW);
	}

	public UpdatePlan plan() {
		return plan;
	}

	public State state() {
		return state;
	}

	public boolean isApproved() {
		return state == State.APPROVED;
	}

	public void approve() {
		if (state != State.PENDING_REVIEW) throw new IllegalStateException("Update plan is not waiting for approval: " + state);
		state = State.APPROVED;
	}

	/** Marks the approved plan as committed-to-execution; cancellation can no longer roll it back. */
	public void beginExecution() {
		if (state != State.APPROVED) throw new IllegalStateException("Only an approved update plan can begin execution: " + state);
		state = State.EXECUTING;
	}

	public void cancel() {
		if (state == State.EXECUTING) return;
		if (state != State.PENDING_REVIEW && state != State.APPROVED) throw new IllegalStateException("Update plan cannot be cancelled: " + state);
		state = State.CANCELLED;
	}

	public void complete() {
		if (state != State.APPROVED && state != State.EXECUTING) throw new IllegalStateException("Only an approved update plan can be completed: " + state);
		state = State.APPLIED;
	}

	/**
	 * Verifies that a plan rebuilt after mutable-input validation still means exactly the same update.
	 * A changed fingerprint must return to the review seam instead of being applied implicitly.
	 */
	public void requireCompatible(UpdatePlan candidate) {
		Objects.requireNonNull(candidate, "candidate plan");
		if (!executionDigest.equals(executionDigest(candidate))) throw new IllegalStateException("The reviewed update plan changed before it could be applied");
	}

	/** Compares a rebuilt plan with the plan captured in a durable transaction. */
	public static boolean isCompatible(UpdateTransaction transaction, UpdatePlan candidate) {
		Objects.requireNonNull(transaction, "transaction");
		Objects.requireNonNull(candidate, "candidate plan");
		return executionDigest(transaction).equals(executionDigest(candidate));
	}

	/** The complete execution meaning of one update, normalized so plans and durable transactions digest identically. */
	private record ExecutionTuple(String modpackId, PackTarget generation, List<Operation> operations, List<ProjectedFile> projected,
			ClientConfigJsons.ClientConfigFieldsV3 config, List<String> restartReasons, List<Preservation> preservations, List<BaselineCapture> baselines,
			List<Conflict> conflicts, List<NestedCopy> nestedCopies, String consequencesDigest) {}

	private static ExecutionTuple tuple(UpdatePlan plan) {
		return new ExecutionTuple(plan.modpackId(), plan.packTarget(), safe(plan.operations()), safe(plan.projectedFinalState()), plan.plannedClientConfig(),
				plan.restartReasons().stream().map(Enum::name).sorted().toList(), safe(plan.preservations()), safe(plan.baselineCaptures()), safe(plan.conflicts()),
				safe(plan.generatedCopies()), consequencesDigest(plan.consequences()));
	}

	private static ExecutionTuple tuple(UpdateTransaction transaction) {
		List<NestedCopy> nestedCopies = transaction.plannedGeneratedCopies == null
				? List.of()
				: safe(transaction.plannedGeneratedCopies.entries).stream().map(entry -> new NestedCopy(entry.logicalPath, entry.sha1, entry.size, Set.of())).toList();
		return new ExecutionTuple(transaction.modpackId, transaction.packTarget(), safe(transaction.operations), safe(transaction.projectedFinalState),
				transaction.plannedClientConfig, safe(transaction.restartReasons).stream().map(Enum::name).sorted().toList(), safe(transaction.plannedPreservations),
				safe(transaction.plannedBaselineCaptures), safe(transaction.plannedConflicts), nestedCopies, transaction.plannedConsequencesDigest);
	}

	public static String executionDigest(UpdatePlan plan) {
		Objects.requireNonNull(plan, "update plan");
		return executionDigest(tuple(plan));
	}

	private static String executionDigest(UpdateTransaction transaction) {
		return executionDigest(tuple(transaction));
	}

	private static String executionDigest(ExecutionTuple tuple) {
		MessageDigest digest = newDigest();
		value(digest, "modpackId", tuple.modpackId());
		generation(digest, tuple.generation());
		values(digest, "operation", tuple.operations(), ReviewedUpdatePlan::operation);
		values(digest, "projected", tuple.projected(), ReviewedUpdatePlan::projected);
		config(digest, tuple.config());
		values(digest, "restart", tuple.restartReasons(), ReviewedUpdatePlan::restartValue);
		values(digest, "preservation", tuple.preservations(), ReviewedUpdatePlan::preservation);
		values(digest, "baseline", tuple.baselines(), ReviewedUpdatePlan::baseline);
		values(digest, "conflict", tuple.conflicts(), ReviewedUpdatePlan::conflict);
		values(digest, "nestedCopy", tuple.nestedCopies(), ReviewedUpdatePlan::nestedCopy);
		value(digest, "consequences", tuple.consequencesDigest());
		return digest(digest);
	}

	public static String consequencesDigest(ChangeSet consequences) {
		Objects.requireNonNull(consequences, "reconciliation consequences");
		MessageDigest digest = newDigest();
		values(digest, "change", consequences.changes(), ReviewedUpdatePlan::change);
		values(digest, "effect", consequences.effects(), ReviewedUpdatePlan::effect);
		return digest(digest);
	}

	private static String change(ChangeSet.Change change) {
		MessageDigest digest = newDigest();
		value(digest, "path", change.logicalPath());
		value(digest, "kind", change.kind());
		values(digest, "occurrence", change.occurrences(), ReviewedUpdatePlan::occurrence);
		return digest(digest);
	}

	private static String occurrence(ChangeSet.Occurrence occurrence) {
		MessageDigest digest = newDigest();
		value(digest, "location", occurrence.location());
		value(digest, "path", occurrence.logicalPath());
		value(digest, "size", occurrence.size());
		value(digest, "before", occurrence.beforeHash());
		value(digest, "after", occurrence.afterHash());
		value(digest, "contentKind", occurrence.contentKind());
		strings(digest, "featureId", occurrence.featureIds());
		strings(digest, "reference", occurrence.references());
		return digest(digest);
	}

	private static String effect(ChangeSet.Effect effect) {
		MessageDigest digest = newDigest();
		value(digest, "category", effect.category());
		value(digest, "value", effect.value());
		return digest(digest);
	}

	private static String operation(UpdatePlan.Operation operation) {
		MessageDigest digest = newDigest();
		value(digest, "root", operation.root());
		value(digest, "path", operation.relativePath());
		value(digest, "type", operation.operation());
		value(digest, "object", operation.expectedObjectHash());
		value(digest, "size", operation.expectedSize());
		value(digest, "existing", operation.expectedExistingHash());
		return digest(digest);
	}

	private static String restartValue(String restartReason) {
		MessageDigest digest = newDigest();
		value(digest, "restartValue", restartReason);
		return digest(digest);
	}

	private static String projected(UpdatePlan.ProjectedFile projected) {
		MessageDigest digest = newDigest();
		value(digest, "root", projected.root());
		value(digest, "path", projected.relativePath());
		value(digest, "present", projected.present());
		value(digest, "hash", projected.expectedHash());
		value(digest, "size", projected.expectedSize());
		return digest(digest);
	}

	private static String preservation(UpdatePlan.Preservation preservation) {
		MessageDigest digest = newDigest();
		value(digest, "root", preservation.root());
		value(digest, "path", preservation.relativePath());
		value(digest, "hash", preservation.expectedHash());
		value(digest, "size", preservation.expectedSize());
		value(digest, "proof", preservation.proof());
		return digest(digest);
	}

	private static String baseline(UpdatePlan.BaselineCapture baseline) {
		MessageDigest digest = newDigest();
		value(digest, "root", baseline.root());
		value(digest, "path", baseline.relativePath());
		value(digest, "hash", baseline.expectedHash());
		value(digest, "size", baseline.expectedSize());
		value(digest, "absent", baseline.absent());
		return digest(digest);
	}

	private static String conflict(UpdatePlan.Conflict conflict) {
		MessageDigest digest = newDigest();
		value(digest, "modpackId", conflict.modpackId());
		value(digest, "id", conflict.conflictId());
		set(digest, "modIds", conflict.modIds());
		value(digest, "sourcePath", conflict.sourcePath());
		value(digest, "sourceHash", conflict.sourceHash());
		value(digest, "sourceSize", conflict.sourceSize());
		value(digest, "targetPath", conflict.targetPath());
		value(digest, "targetHash", conflict.targetHash());
		value(digest, "targetSize", conflict.targetSize());
		value(digest, "action", conflict.action());
		return digest(digest);
	}

	private static String nestedCopy(UpdatePlan.NestedCopy copy) {
		MessageDigest digest = newDigest();
		value(digest, "path", copy.relativePath());
		value(digest, "hash", copy.sha1());
		value(digest, "size", copy.size());
		// Durable generated-copy state persists the loader-facing execution tuple, not inspection-only IDs.
		return digest(digest);
	}

	private static void generation(MessageDigest digest, PackTarget generation) {
		value(digest, "generationModpack", generation.modpackId());
		value(digest, "contentToken", generation.contentToken());
		value(digest, "policySha1", generation.policySha1());
		value(digest, "ledgerDigest", generation.ledgerDigest());
	}

	private static void config(MessageDigest digest, ClientConfigJsons.ClientConfigFieldsV3 config) {
		if (config == null) {
			value(digest, "config", "null");
			return;
		}
		value(digest, "selectedModpackId", config.selectedModpackId);
		value(digest, "updateSelectedModpackOnLaunch", config.updateSelectedModpackOnLaunch);
		value(digest, "selfUpdater", config.selfUpdater);
		value(digest, "syncAutoModpackVersion", config.syncAutoModpackVersion);
		value(digest, "syncLoaderVersion", config.syncLoaderVersion);
		value(digest, "playMusic", config.playMusic);
		value(digest, "showModpackSettingsButton", config.showModpackSettingsButton);
	}

	private static <T> void values(MessageDigest digest, String label, List<T> values, Encoder<T> encoder) {
		List<String> encoded = new ArrayList<>();
		for (T item : safe(values)) encoded.add(encoder.encode(item));
		encoded.sort(Comparator.naturalOrder());
		value(digest, label + "Count", encoded.size());
		for (String item : encoded) value(digest, label + "Value", item);
	}

	private static void set(MessageDigest digest, String label, Set<String> values) {
		List<String> sorted = values == null ? List.of() : values.stream().filter(Objects::nonNull).map(value -> value.toLowerCase(Locale.ROOT)).sorted().toList();
		value(digest, label + "Count", sorted.size());
		for (String item : sorted) value(digest, label + "Value", item);
	}

	private static void strings(MessageDigest digest, String label, List<String> values) {
		List<String> sorted = values == null ? List.of() : values.stream().filter(Objects::nonNull).sorted().toList();
		value(digest, label + "Count", sorted.size());
		for (String item : sorted) value(digest, label + "Value", item);
	}

	private static <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	private static void value(MessageDigest digest, String label, Object value) {
		String encoded = String.valueOf(value);
		byte[] bytes = (label + "\u0000" + encoded).getBytes(StandardCharsets.UTF_8);
		digest.update((byte) (bytes.length >>> 24));
		digest.update((byte) (bytes.length >>> 16));
		digest.update((byte) (bytes.length >>> 8));
		digest.update((byte) bytes.length);
		digest.update(bytes);
	}

	private static String digest(MessageDigest digest) {
		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest newDigest() {
		return HashUtils.newSha1Digest();
	}

	@FunctionalInterface
	private interface Encoder<T> {
		String encode(T value);
	}

	public enum State {
		PENDING_REVIEW,
		APPROVED,
		EXECUTING,
		APPLIED,
		CANCELLED
	}
}
