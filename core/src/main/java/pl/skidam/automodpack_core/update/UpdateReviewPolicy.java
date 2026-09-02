package pl.skidam.automodpack_core.update;

import java.util.Objects;

import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;

/** Defines when an update must be presented for player approval. */
public final class UpdateReviewPolicy {
	private UpdateReviewPolicy() {}

	/**
	 * A generation identity change is reviewable even when the resulting file plan is empty because
	 * the player must be able to inspect and accept the new generation's metadata and patch notes.
	 */
	public static boolean requiresPlayerReview(boolean firstInstall, GenerationTarget installedTarget, GenerationTarget advertisedTarget, boolean planImpact) {
		return requiresPlayerReview(firstInstall, installedTarget, advertisedTarget, planImpact, true);
	}

	/** Returns whether the review gate is needed after applying the client-local review preference. */
	public static boolean requiresPlayerReview(boolean firstInstall, GenerationTarget installedTarget, GenerationTarget advertisedTarget, boolean planImpact,
			boolean reviewUpdates) {
		Objects.requireNonNull(advertisedTarget, "advertised target");
		if (!reviewUpdates) return false;
		return firstInstall || installedTarget == null || !installedTarget.equals(advertisedTarget) || planImpact;
	}
}
