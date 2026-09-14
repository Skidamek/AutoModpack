package pl.skidam.automodpack_core.screen;

import java.util.List;

import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePreview;

/**
 * One reviewable plan crossing the screen seam. {@code writesUnverifiedJar} is precomputed by the flow (mode-gated),
 * so the screen routes between the concise preview and the typed-ack confirm without touching the engine. {@code target}
 * and {@code actions} are null when no review-backed session stands behind the plan, e.g. storage-only removal previews.
 * {@code origin} is the join target the plan arrived from, or "" when the session has no live connection.
 */
public record PreviewPayload(UpdatePreview preview, String modpackName, String origin, boolean writesUnverifiedJar, SelectedModpackTarget target, List<String> unverifiedJarPaths,
		SourceCounts sourceCounts, ReviewActions actions, Runnable continueAction, Runnable cancelAction) {}
