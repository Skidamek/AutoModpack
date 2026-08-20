package pl.skidam.automodpack_core.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;

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

	public static ReviewedUpdatePlan approved(UpdatePlan plan) {
		return new ReviewedUpdatePlan(plan, State.APPROVED);
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

	public void cancel() {
		if (state != State.PENDING_REVIEW && state != State.APPROVED) throw new IllegalStateException("Update plan cannot be cancelled: " + state);
		state = State.CANCELLED;
	}

	public void complete() {
		if (state != State.APPROVED) throw new IllegalStateException("Only an approved update plan can be completed: " + state);
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

	public static String executionDigest(UpdatePlan plan) {
		Objects.requireNonNull(plan, "update plan");
		MessageDigest digest = newDigest();
		value(digest, "modpackId", plan.modpackId());
		generation(digest, plan.generationTarget());
		values(digest, "operation", plan.operations(), ReviewedUpdatePlan::operation);
		values(digest, "projected", plan.projectedFinalState(), ReviewedUpdatePlan::projected);
		config(digest, plan.plannedClientConfig());
		values(digest, "restart", plan.restartReasons().stream().map(Enum::name).sorted().toList(), value -> restartValue(value));
		values(digest, "preservation", plan.preservations(), ReviewedUpdatePlan::preservation);
		values(digest, "baseline", plan.baselineCaptures(), ReviewedUpdatePlan::baseline);
		values(digest, "conflict", plan.conflicts(), ReviewedUpdatePlan::conflict);
		values(digest, "nestedCopy", plan.generatedCopies(), ReviewedUpdatePlan::nestedCopy);
		value(digest, "consequences", consequencesDigest(plan.consequences()));
		return digest(digest);
	}

	private static String executionDigest(UpdateTransaction transaction) {
		MessageDigest digest = newDigest();
		value(digest, "modpackId", transaction.modpackId);
		generation(digest, transaction.generationTarget());
		values(digest, "operation", safe(transaction.operations), ReviewedUpdatePlan::operation);
		values(digest, "projected", safe(transaction.projectedFinalState), ReviewedUpdatePlan::projected);
		config(digest, transaction.plannedClientConfig);
		values(digest, "restart", safe(transaction.restartReasons).stream().map(Enum::name).sorted().toList(), value -> restartValue(value));
		values(digest, "preservation", safe(transaction.plannedPreservations), ReviewedUpdatePlan::preservation);
		values(digest, "baseline", safe(transaction.plannedBaselineCaptures), ReviewedUpdatePlan::baseline);
		values(digest, "conflict", safe(transaction.plannedConflicts), ReviewedUpdatePlan::conflict);
		values(digest, "nestedCopy", transaction.plannedGeneratedCopies == null ? List.of() : safe(transaction.plannedGeneratedCopies.entries), ReviewedUpdatePlan::generatedCopyEntry);
		value(digest, "consequences", transaction.plannedConsequencesDigest);
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

	private static String generatedCopyEntry(ClientStorageJsons.ClientGeneratedCopiesFields.EntryFields entry) {
		MessageDigest digest = newDigest();
		value(digest, "path", entry.logicalPath);
		value(digest, "hash", entry.sha1);
		value(digest, "size", entry.size);
		return digest(digest);
	}

	private static void generation(MessageDigest digest, GenerationTarget generation) {
		value(digest, "generationModpack", generation.modpackId());
		value(digest, "generationId", generation.targetGenerationId());
		value(digest, "parentGenerationId", generation.parentGenerationId());
		value(digest, "stateDigest", generation.stateDigest());
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
	}

	private static <T> void values(MessageDigest digest, String label, List<T> values, Encoder<T> encoder) {
		List<String> encoded = new ArrayList<>();
		for (T item : safe(values)) encoded.add(encoder.encode(item));
		encoded.sort(Comparator.naturalOrder());
		value(digest, label + "Count", encoded.size());
		for (String item : encoded) value(digest, label + "Value", item);
	}

	private static void set(MessageDigest digest, String label, java.util.Set<String> values) {
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
		return java.util.HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-1 is required by the client protocol", e);
		}
	}

	@FunctionalInterface
	private interface Encoder<T> {
		String encode(T value);
	}

	public enum State {
		PENDING_REVIEW,
		APPROVED,
		APPLIED,
		CANCELLED
	}
}
