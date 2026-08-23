package pl.skidam.automodpack.client.ui.screen;

import java.io.IOException;

import net.minecraft.client.Minecraft;

import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.StoredModpackConnection;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Switches an installed generation, reusing local objects and connecting only when the selected target needs more. */
final class InstalledModpackSwitch {
	private InstalledModpackSwitch() {}

	static void start(ClientStorage storage, GenerationRecord record, SelectionIntent expectedSelection, SelectionIntent targetSelection,
			String modpackName, Runnable release) {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ModpackUpdater updater = null;
			try {
				var fields = new ClientGenerationStore(storage).readFields(record.metadata().generationId())
						.orElseThrow(() -> new IOException("Installed modpack generation record is missing"));
				SelectedModpackTarget target = SelectedModpackTarget.prepare(fields, expectedSelection, targetSelection, ClientPlatform.current());
				updater = updater(storage, target);
				UpdatePreview preview = updater.previewInstalledSwitch();
				ModpackUpdater finalUpdater = updater;
				boolean shown = ScreenManager.preview(preview, modpackName,
						(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> apply(finalUpdater, release)),
						(Runnable) () -> {
							finalUpdater.close();
							release.run();
						});
				if (!shown) {
					finalUpdater.close();
					Minecraft.getInstance().execute(release);
				}
			} catch (Exception e) {
				if (updater != null) updater.close();
				release.run();
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}

	private static ModpackUpdater updater(ClientStorage storage, SelectedModpackTarget target) throws Exception {
		ModpackUpdater local = new ModpackUpdater(target, null, null, storage);
		if (!local.requiresSelectedTargetDownload()) return local;
		local.close();

		try (StoredModpackConnection connection = StoredModpackConnection.open(storage, target.manifest().modpackId(), true)) {
			return connection.newUpdater(target, storage);
		}
	}

	private static void apply(ModpackUpdater updater, Runnable release) {
		try {
			updater.applyInstalledSwitch();
		} catch (Exception e) {
			updater.close();
			release.run();
			ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
		}
	}
}
