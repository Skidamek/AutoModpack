package pl.skidam.automodpack_core.screen;

import java.util.List;

import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePreview;

/**
 * One reviewable plan crossing the screen seam, built through its two roles: {@link #review} for a plan with a live
 * review session behind it, {@link #storageRemoval} for removing or deactivating a stored pack. {@code
 * writesUnverifiedJar} is precomputed by the flow (mode-gated), so the screen routes between the concise preview and
 * the typed-ack confirm without touching the engine. {@code origin} is the join target the plan arrived from, or ""
 * when the session has no live connection.
 */
public record PreviewPayload(UpdatePreview preview, String modpackName, String origin, boolean writesUnverifiedJar, SelectedModpackTarget target, List<String> unverifiedJarPaths,
		SourceCounts sourceCounts, ReviewActions actions, Runnable continueAction, Runnable cancelAction) {

	/** A plan a live review session stands behind: the screen can reach the review's customize actions through it. */
	public static PreviewPayload review(UpdatePreview preview, String modpackName, String origin, boolean writesUnverifiedJar, SelectedModpackTarget target, List<String> unverifiedJarPaths,
			SourceCounts sourceCounts, ReviewActions actions, Runnable continueAction, Runnable cancelAction) {
		return new PreviewPayload(preview, modpackName, origin, writesUnverifiedJar, target, unverifiedJarPaths, sourceCounts, actions, continueAction, cancelAction);
	}

	/** Removing or deactivating a stored pack: no jar re-verification question, {@code actions} null when no live review offers customize. */
	public static PreviewPayload storageRemoval(UpdatePreview preview, String modpackName, String origin, ReviewActions actions, Runnable continueAction, Runnable cancelAction) {
		return new PreviewPayload(preview, modpackName, origin, false, null, List.of(), null, actions, continueAction, cancelAction);
	}
}
