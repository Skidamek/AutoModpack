package pl.skidam.automodpack_core.update;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan.NestedCopy;
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
		List<String> drifted = outcomeDrift(plan, candidate);
		if (!drifted.isEmpty()) throw new IllegalStateException("The reviewed update outcome changed before it could be applied: " + String.join(", ", drifted));
	}

	/**
	 * Whether a rebuilt plan still means the same approved update. Live replan and boot recovery both call this, so an
	 * interrupted apply can never pass on one path and loop the boot on the other.
	 */
	public static boolean outcomeCompatible(UpdatePlan approved, UpdatePlan rebuilt) {
		return outcomeDrift(approved, rebuilt).isEmpty();
	}

	private static List<String> outcomeDrift(UpdatePlan approved, UpdatePlan rebuilt) {
		Objects.requireNonNull(approved, "approved plan");
		Objects.requireNonNull(rebuilt, "candidate plan");
		OutcomeTuple left = outcomeTuple(approved);
		OutcomeTuple right = outcomeTuple(rebuilt);
		List<String> drifted = new ArrayList<>();
		if (!left.modpackId().equals(right.modpackId())) drifted.add("modpack");
		if (!left.generation().equals(right.generation())) drifted.add("target generation");
		if (!left.projected().equals(right.projected())) drifted.add("projected final state");
		if (!left.generatedCopies().equals(right.generatedCopies())) drifted.add("generated copies");
		if (!Objects.equals(left.config(), right.config())) drifted.add("planned client configuration");
		return drifted;
	}

	/**
	 * The approved outcome: destination files that remain, never leftover absent rows from already-applied deletes, and
	 * the generated-copy index finalize will write (path/sha1/size, never NestedCopy.ids).
	 */
	private record OutcomeTuple(String modpackId, PackTarget generation, List<ProjectedFile> projected, List<GeneratedCopyIdentity> generatedCopies,
			ClientConfigJsons.ClientConfigFieldsV3 config) {}

	/** Path, hash, and size of one generated copy. NestedCopy.ids are transient and empty after persist. */
	private record GeneratedCopyIdentity(String relativePath, String sha1, long size) {}

	private static OutcomeTuple outcomeTuple(UpdatePlan plan) {
		return new OutcomeTuple(plan.modpackId(), plan.packTarget(), destination(plan), generatedCopies(plan), plan.plannedClientConfig());
	}

	private static List<ProjectedFile> destination(UpdatePlan plan) {
		List<ProjectedFile> present = new ArrayList<>();
		if (plan.projectedFinalState() != null) for (ProjectedFile file : plan.projectedFinalState()) if (file != null && file.present()) present.add(file);
		present.sort(Comparator.comparingInt((ProjectedFile file) -> file.root().ordinal()).thenComparing(ProjectedFile::relativePath));
		return present;
	}

	private static List<GeneratedCopyIdentity> generatedCopies(UpdatePlan plan) {
		List<GeneratedCopyIdentity> copies = new ArrayList<>();
		if (plan.generatedCopies() != null)
			for (NestedCopy copy : plan.generatedCopies())
				if (copy != null) copies.add(new GeneratedCopyIdentity(copy.relativePath(), copy.sha1(), copy.size()));
		copies.sort(Comparator.comparing(GeneratedCopyIdentity::relativePath));
		return copies;
	}

	public enum State {
		PENDING_REVIEW,
		APPROVED,
		EXECUTING,
		APPLIED,
		CANCELLED
	}
}
