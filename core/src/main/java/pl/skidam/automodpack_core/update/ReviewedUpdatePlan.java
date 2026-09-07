package pl.skidam.automodpack_core.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
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
				loaderCopies(plan.generatedCopies()), consequencesDigest(plan.consequences()));
	}

	private static ExecutionTuple tuple(UpdateTransaction transaction) {
		List<NestedCopy> nestedCopies = transaction.plannedGeneratedCopies == null
				? List.of()
				: safe(transaction.plannedGeneratedCopies.entries).stream().map(entry -> new NestedCopy(entry.logicalPath, entry.sha1, entry.size, Set.of())).toList();
		return new ExecutionTuple(transaction.modpackId, transaction.packTarget(), safe(transaction.operations), safe(transaction.projectedFinalState),
				transaction.plannedClientConfig, safe(transaction.restartReasons).stream().map(Enum::name).sorted().toList(), safe(transaction.plannedPreservations),
				safe(transaction.plannedBaselineCaptures), safe(transaction.plannedConflicts), nestedCopies, transaction.plannedConsequencesDigest);
	}

	/**
	 * Durable generated-copy state persists the loader-facing execution tuple, not inspection-only IDs,
	 * so both plan- and transaction-side copies are stripped to their identical loader-facing fields.
	 */
	private static List<NestedCopy> loaderCopies(List<NestedCopy> copies) {
		return safe(copies).stream().map(copy -> new NestedCopy(copy.relativePath(), copy.sha1(), copy.size(), Set.of())).toList();
	}

	public static String executionDigest(UpdatePlan plan) {
		Objects.requireNonNull(plan, "update plan");
		return executionDigest(tuple(plan));
	}

	private static String executionDigest(UpdateTransaction transaction) {
		return executionDigest(tuple(transaction));
	}

	/** Every tuple field is encoded as one length-prefixed unit: the records' canonical toStrings. */
	private static String executionDigest(ExecutionTuple tuple) {
		MessageDigest digest = newDigest();
		value(digest, "modpackId", tuple.modpackId());
		value(digest, "generation", tuple.generation());
		values(digest, "operation", tuple.operations());
		values(digest, "projected", tuple.projected());
		value(digest, "config", tuple.config());
		values(digest, "restart", tuple.restartReasons());
		values(digest, "preservation", tuple.preservations());
		values(digest, "baseline", tuple.baselines());
		values(digest, "conflict", tuple.conflicts());
		values(digest, "nestedCopy", tuple.nestedCopies());
		value(digest, "consequences", tuple.consequencesDigest());
		return digest(digest);
	}

	public static String consequencesDigest(ChangeSet consequences) {
		Objects.requireNonNull(consequences, "reconciliation consequences");
		MessageDigest digest = newDigest();
		values(digest, "change", consequences.changes());
		values(digest, "effect", consequences.effects());
		return digest(digest);
	}

	private static <T> void values(MessageDigest digest, String label, List<T> values) {
		List<String> encoded = new ArrayList<>();
		for (T item : safe(values)) encoded.add(String.valueOf(item));
		encoded.sort(Comparator.naturalOrder());
		value(digest, label + "Count", encoded.size());
		for (String item : encoded) value(digest, label + "Value", item);
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

	public enum State {
		PENDING_REVIEW,
		APPROVED,
		EXECUTING,
		APPLIED,
		CANCELLED
	}
}
