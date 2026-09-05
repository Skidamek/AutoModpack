package pl.skidam.automodpack_loader_core.client;

import java.util.Objects;

import pl.skidam.automodpack_core.update.ReviewedUpdatePlan;

/** Keeps the prepared side effects and the player-facing review as one finite update session. */
record ReviewedClientPlan<T>(T prepared, ReviewedUpdatePlan review) {
	ReviewedClientPlan {
		Objects.requireNonNull(prepared, "prepared update");
		Objects.requireNonNull(review, "reviewed plan");
	}
}
