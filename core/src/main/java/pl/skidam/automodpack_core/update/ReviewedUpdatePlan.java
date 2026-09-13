package pl.skidam.automodpack_core.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;

/**
 * Owns the finite lifecycle and the outcome contract of one player-reviewed update plan. The review approves the
 * outcome - the target generation, the projected final state, and the planned client configuration - never the work
 * description, so a plan rebuilt after a partial apply is judged by the one predicate both the live replan seam and
 * boot recovery share: {@link #outcomeCompatible(UpdatePlan, UpdatePlan)}.
 */
public final class ReviewedUpdatePlan {
	private final UpdatePlan plan;
	private State state;

	private ReviewedUpdatePlan(UpdatePlan plan, State state) {
		this.plan = Objects.requireNonNull(plan, "update plan");
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
	 * Verifies that a plan rebuilt after a partial apply still means exactly the update the player approved. An
	 * earlier attempt of this same approved plan may have already applied a prefix of its operations, which
	 * legitimately shrinks the rebuilt plan's operations, consequences, and captures, and must not read as a changed
	 * review. A drifted outcome returns to the review seam instead of being applied implicitly, and the failure names
	 * exactly what drifted.
	 */
	public void requireCompatible(UpdatePlan candidate) {
		Objects.requireNonNull(candidate, "candidate plan");
		OutcomeTuple approved = outcomeTuple(plan);
		OutcomeTuple rebuilt = outcomeTuple(candidate);
		List<String> drifted = new ArrayList<>();
		if (!approved.modpackId().equals(rebuilt.modpackId())) drifted.add("modpack");
		if (!approved.generation().equals(rebuilt.generation())) drifted.add("target generation");
		if (!approved.projected().equals(rebuilt.projected())) drifted.add("projected final state");
		if (!Objects.equals(approved.config(), rebuilt.config())) drifted.add("planned client configuration");
		if (!drifted.isEmpty()) throw new IllegalStateException("The reviewed update outcome changed before it could be applied: " + String.join(", ", drifted));
	}

	/**
	 * Whether a rebuilt plan still means the same approved update. Used by the live replan seam through
	 * {@link #requireCompatible(UpdatePlan)} and by boot recovery directly, so an interrupted apply can never pass on
	 * one path and loop the boot on the other.
	 */
	public static boolean outcomeCompatible(UpdatePlan approved, UpdatePlan rebuilt) {
		return outcomeTuple(approved).equals(outcomeTuple(rebuilt));
	}

	/** The approved outcome of one update: everything the player's review decided, independent of how much work is still ahead. */
	private record OutcomeTuple(String modpackId, PackTarget generation, List<ProjectedFile> projected, ClientConfigJsons.ClientConfigFieldsV3 config) {}

	private static OutcomeTuple outcomeTuple(UpdatePlan plan) {
		return new OutcomeTuple(plan.modpackId(), plan.packTarget(), safe(plan.projectedFinalState()), plan.plannedClientConfig());
	}

	private static <T> List<T> safe(List<T> values) {
		return values == null ? List.of() : values;
	}

	public enum State {
		PENDING_REVIEW,
		APPROVED,
		EXECUTING,
		APPLIED,
		CANCELLED
	}
}
