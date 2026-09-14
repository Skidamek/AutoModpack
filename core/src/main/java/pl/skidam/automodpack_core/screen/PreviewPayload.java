package pl.skidam.automodpack_core.screen;

import java.util.List;

import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePreview;

/**
 * One reviewable plan crossing the screen seam. {@code writesUnverifiedJar} is precomputed by the flow (mode-gated),
 * so the screen routes between the concise preview and the typed-ack confirm without touching the engine. {@code target}
 * and {@code actions} are null when no review-backed session stands behind the plan, e.g. storage-only removal previews.
 */
public record PreviewPayload(UpdatePreview preview, String modpackName, boolean writesUnverifiedJar, SelectedModpackTarget target, List<String> unverifiedJarPaths,
		ReviewActions actions, Runnable continueAction, Runnable cancelAction) {}
