package pl.skidam.automodpack_core.client;

import pl.skidam.automodpack_core.screen.PreviewPayload;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.RestartDemand;
import pl.skidam.automodpack_core.update.RestartPolicy;
import pl.skidam.automodpack_core.update.UpdatePreview;

/**
 * Modpack lifecycle: removal and deactivation of the installed modpack. SwitchFlow's sibling: the flow owns the dance -
 * prepare, preview, confirm, commit, and the removal apply tail - while the facade keeps the shared attempt bookkeeping
 * and the restart machinery. {@link RemovalAttempt} stays the approve/commit seam under the flow.
 */
final class LifecycleFlow {
	private final ModpackUpdater updater;
	private final ClientStorage storage;
	private final ClientUpdatePlanBuilder planBuilder;
	private final Changelogs changelogs;

	LifecycleFlow(ModpackUpdater updater, ClientStorage storage, ClientUpdatePlanBuilder planBuilder, Changelogs changelogs) {
		this.updater = updater;
		this.storage = storage;
		this.planBuilder = planBuilder;
		this.changelogs = changelogs;
	}

	/**
	 * Reviews then commits the active pack's removal or deactivation: the preview takes the screen, the confirm click
	 * commits, and the caller's released (always) and removed (only when navigation makes sense) run on the client thread.
	 */
	void removeOrDeactivate(boolean deactivation, String modpackName, Runnable released, Runnable removed) {
		updater.executor().execute(() -> {
			try {
				UpdatePreview preview = preview(deactivation ? RemovalAttempt.Kind.DEACTIVATION : RemovalAttempt.Kind.REMOVAL);
				boolean shown = ScreenManager.preview(PreviewPayload.storageRemoval(preview, modpackName, updater.joinOrigin(), updater.reviewActions(),
						(Runnable) () -> ModpackUpdater.executor().execute(() -> execute(deactivation, released, removed)), released));
				if (!shown) {
					updater.close();
					ScreenManager.clientThread(released);
				}
			} catch (Exception e) {
				updater.close();
				ScreenManager.clientThread(released);
				ModpackUpdater.showUpdateFailure(e);
			}
		});
	}

	private void execute(boolean deactivation, Runnable released, Runnable removed) {
		boolean finishedWithoutRestart = false;
		try {
			RemovalAttempt removal = updater.requireRemoval(deactivation ? RemovalAttempt.Kind.DEACTIVATION : RemovalAttempt.Kind.REMOVAL);
			// The confirm click is the review's consent; commit itself refuses an unapproved plan.
			removal.approve();
			RestartDecision.ApplyResult result = updater.commitFlow(removal);
			RestartDemand demand = RestartPolicy.inGame(result.restartReasons(), changelogs.changedOrRemovedPaths());
			// Navigation happens only when nothing supersedes it: a restart demand takes the screen.
			finishedWithoutRestart = removed != null && demand == RestartDemand.NONE;
			afterApply(result, demand);
		} catch (Exception e) {
			ModpackUpdater.showUpdateFailure(e);
		} finally {
			updater.close();
			boolean navigate = finishedWithoutRestart;
			ScreenManager.clientThread(() -> {
				released.run();
				if (navigate) removed.run();
			});
		}
	}

	private UpdatePreview preview(RemovalAttempt.Kind kind) throws Exception {
		return updater.beginAttempt(new RemovalAttempt(storage, planBuilder, changelogs, kind)).preview();
	}

	/** Removal has no in-game content load: only a non-none in-game demand asks the player to restart. */
	private void afterApply(RestartDecision.ApplyResult result, RestartDemand demand) {
		if (demand != RestartDemand.NONE) updater.restartAfterApply(result);
		else updater.clearUpdateLoopDetector();
	}
}
