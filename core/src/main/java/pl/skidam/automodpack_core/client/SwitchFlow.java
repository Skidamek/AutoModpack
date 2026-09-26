package pl.skidam.automodpack_core.client;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.PreviewPayload;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePreview;

/**
 * The installed-generation switch and its rollback: resolve the target, review the switch plan, and apply it. Both
 * entries own the whole dance - preview, continue/cancel, failure, close, and the caller's release - so no screen
 * assembles it from engine parts. The release always lands on the client thread.
 */
public final class SwitchFlow {
	private SwitchFlow() {}

	/** Switches an installed generation, reusing local objects and connecting only when the selected target needs more. */
	public static void start(ClientStorage storage, PackDocument record, SelectionIntent expectedSelection, SelectionIntent targetSelection, String modpackName, Runnable release) {
		ModpackUpdater.executor().execute(() -> {
			ModpackUpdater updater = null;
			try {
				SelectedModpackTarget target = SelectedModpackTarget.prepare(record, expectedSelection, targetSelection, ClientPlatform.effective(targetSelection));
				updater = updater(storage, target);
				preview(updater, modpackName, release, null, false);
			} catch (Exception e) {
				if (updater != null) updater.close();
				ScreenManager.clientThread(release);
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}

	/**
	 * Rolls the active pack back to one journal generation: the target document is reconstructed offline from the
	 * mirror and CAS, then the normal reviewed switch transaction previews and applies it. The rollback declares the
	 * pack detached; the server hosts only head objects, so a generation whose bytes the client no longer holds fails
	 * loudly instead of syncing.
	 */
	public static void rollback(ClientStorage storage, String modpackId, JournalEntry entry, String modpackName, Runnable release) {
		ModpackUpdater.executor().execute(() -> {
			ModpackUpdater updater = null;
			try {
				PackDocument record = new ClientGenerationStore(storage).document(modpackId, entry);
				SelectionIntent savedSelection = new ClientSelectionStore(storage.selectionFile()).get(modpackId).orElse(null);
				SelectionIntent targetSelection = savedSelection == null ? GroupSelectionResolver.defaultIntent(record.manifest()) : savedSelection;
				SelectedModpackTarget target = SelectedModpackTarget.prepare(record, savedSelection, targetSelection, ClientPlatform.effective(targetSelection));
				updater = new ModpackUpdater(target, null, null, storage);
				if (updater.requiresSelectedTargetDownload())
					throw new IOException("This version's files are no longer kept on this computer, so it cannot be restored");
				preview(updater, modpackName, release, UpdatePreview.Mode.ROLLBACK, true);
			} catch (Exception e) {
				if (updater != null) updater.close();
				ScreenManager.clientThread(release);
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}

	private static void preview(ModpackUpdater updater, String modpackName, Runnable release, UpdatePreview.Mode forcedMode, boolean rollback) throws Exception {
		UpdatePreview preview = updater.previewInstalledSwitch();
		if (forcedMode != null) preview = preview.withMode(forcedMode);
		boolean writesUnverifiedJar = (preview.mode() == UpdatePreview.Mode.UPDATE || preview.mode() == UpdatePreview.Mode.ROLLBACK) && updater.planWritesUnverifiedJar(preview.plan());
		// One apply per review: a double confirm click must not start a second commit on the same updater.
		AtomicBoolean applyArmed = new AtomicBoolean(true);
		boolean shown = ScreenManager.preview(PreviewPayload.review(preview, modpackName, updater.joinOrigin(), writesUnverifiedJar, updater.getSelectedTarget(), updater.unverifiedSelectedJarPaths(),
				updater.selectedJarSourceCounts(), updater.reviewActions(),
				(Runnable) () -> {
					if (!applyArmed.compareAndSet(true, false)) return;
					ModpackUpdater.executor().execute(() -> apply(updater, release, rollback));
				},
				(Runnable) () -> {
					updater.close();
					ScreenManager.clientThread(release);
				}));
		if (!shown) {
			updater.close();
			ScreenManager.clientThread(release);
		}
	}

	private static ModpackUpdater updater(ClientStorage storage, SelectedModpackTarget target) throws Exception {
		ModpackUpdater local = new ModpackUpdater(target, null, null, storage);
		if (!local.requiresSelectedTargetDownload()) return local;
		local.close();

		try (StoredModpackConnection connection = StoredModpackConnection.open(storage, target.manifest().modpackId(), true)) {
			return connection.newUpdater(target, storage);
		}
	}

	private static void apply(ModpackUpdater updater, Runnable release, boolean rollback) {
		try {
			// The rollback's apply is the declaration of local sovereignty: it detaches before the commit.
			if (rollback) updater.applyGenerationRollback();
			else updater.applyInstalledSwitch();
		} catch (Exception e) {
			updater.close();
			ScreenManager.clientThread(release);
			ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
		}
	}
}
