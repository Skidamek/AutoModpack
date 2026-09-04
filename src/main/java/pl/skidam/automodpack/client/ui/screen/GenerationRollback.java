package pl.skidam.automodpack.client.ui.screen;

import java.io.IOException;

import net.minecraft.client.Minecraft;

import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Rolls the active pack back to one journal generation: the target document is reconstructed offline from the
 * mirror and CAS, then the normal reviewed switch transaction previews and applies it. The rollback declares the
 * pack detached; the server hosts only head objects, so a generation whose bytes the client no longer holds fails
 * loudly instead of syncing.
 */
final class GenerationRollback {
	private GenerationRollback() {}

	static void start(ClientStorage storage, String modpackId, JournalEntry entry, String modpackName, Runnable release) {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ModpackUpdater updater = null;
			try {
				PackDocument record = new ClientGenerationStore(storage).document(modpackId, entry);
				SelectionIntent savedSelection = new ClientSelectionStore(storage.selectionFile()).get(modpackId).orElse(null);
				SelectionIntent targetSelection = savedSelection == null ? GroupSelectionResolver.defaultIntent(record.manifest()) : savedSelection;
				SelectedModpackTarget target = SelectedModpackTarget.prepare(record, savedSelection, targetSelection, ClientPlatform.effective(targetSelection));
				updater = new ModpackUpdater(target, null, null, storage);
				if (updater.requiresSelectedTargetDownload())
					throw new IOException("This version's files are no longer kept on this computer, so it cannot be restored");
				UpdatePreview preview = updater.previewInstalledSwitch();
				ModpackUpdater finalUpdater = updater;
				boolean shown = ScreenManager.preview(preview, modpackName, finalUpdater,
						(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> apply(finalUpdater, release)),
						(Runnable) () -> {
							finalUpdater.close();
							Minecraft.getInstance().execute(release);
						});
				if (!shown) {
					finalUpdater.close();
					Minecraft.getInstance().execute(release);
				}
			} catch (Exception e) {
				if (updater != null) updater.close();
				Minecraft.getInstance().execute(release);
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}

	private static void apply(ModpackUpdater updater, Runnable release) {
		try {
			updater.applyGenerationRollback();
		} catch (Exception e) {
			updater.close();
			Minecraft.getInstance().execute(release);
			ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
		}
	}
}
