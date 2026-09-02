package pl.skidam.automodpack_loader_core.client;

import java.util.Objects;

import pl.skidam.automodpack_core.update.ReviewedUpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlan;

/** Keeps the prepared side effects and the player-facing review as one finite update session. */
final class ReviewedClientPlan<T> {
	private final T prepared;
	private final ReviewedUpdatePlan review;

	private ReviewedClientPlan(T prepared, UpdatePlan plan) {
		this.prepared = Objects.requireNonNull(prepared, "prepared update");
		this.review = ReviewedUpdatePlan.pending(Objects.requireNonNull(plan, "reviewed plan"));
	}

	static <T> ReviewedClientPlan<T> pending(T prepared, UpdatePlan plan) {
		return new ReviewedClientPlan<>(prepared, plan);
	}

	static <T> ReviewedClientPlan<T> approved(T prepared, UpdatePlan plan) {
		ReviewedClientPlan<T> session = new ReviewedClientPlan<>(prepared, plan);
		session.approve();
		return session;
	}

	T prepared() {
		return prepared;
	}

	UpdatePlan plan() {
		return review.plan();
	}

	boolean isApproved() {
		return review.isApproved();
	}

	void approve() {
		review.approve();
	}

	void cancel() {
		review.cancel();
	}

	void complete() {
		review.complete();
	}

	void requireCompatible(UpdatePlan candidate) {
		review.requireCompatible(candidate);
	}
}
