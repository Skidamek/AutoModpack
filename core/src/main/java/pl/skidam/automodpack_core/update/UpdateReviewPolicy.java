package pl.skidam.automodpack_core.update;

import java.util.Objects;

import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;

/** Defines when an in-game update must be presented for player approval. Launch apply does not use this policy. */
public final class UpdateReviewPolicy {
	private UpdateReviewPolicy() {}

	/**
	 * In-game apply shows a review for first install, a missing or different generation identity, or any plan impact.
	 * A generation identity change is reviewable even when the file plan is empty so the player can inspect metadata
	 * and patch notes. {@code updateSelectedModpackOnLaunch} applies during preload without calling this method.
	 */
	public static boolean requiresPlayerReview(boolean firstInstall, GenerationTarget installedTarget, GenerationTarget advertisedTarget, boolean planImpact) {
		Objects.requireNonNull(advertisedTarget, "advertised target");
		return firstInstall || installedTarget == null || !installedTarget.equals(advertisedTarget) || planImpact;
	}
}
