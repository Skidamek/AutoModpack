package pl.skidam.automodpack.client.ui;

import java.io.IOException;
import java.util.Map;

import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

final class CachedModpackSwitch {
	private CachedModpackSwitch() {}

	static void start(ClientStorage storage, GenerationRecord record, SelectionIntent expectedSelection, SelectionIntent targetSelection,
			String modpackName, boolean returnToSelection, Runnable release) {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ModpackUpdater updater = null;
			try {
				var fields = new ClientGenerationStore(storage).readFields(record.metadata().generationId())
						.orElseThrow(() -> new IOException("Installed modpack generation record is missing"));
				SelectedModpackTarget target = SelectedModpackTarget.prepare(fields, expectedSelection, targetSelection, ClientPlatform.current());
				updater = new ModpackUpdater(target, null, null, storage);
				UpdatePreview preview = updater.previewCachedSwitch();
				ModpackUpdater finalUpdater = updater;
				new ScreenManager().preview(preview, modpackName,
						(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> apply(finalUpdater, release)),
						(Runnable) () -> {
							finalUpdater.close();
							release.run();
						}, returnToSelection, Map.of());
			} catch (Exception e) {
				if (updater != null) updater.close();
				release.run();
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private static void apply(ModpackUpdater updater, Runnable release) {
		try {
			updater.applyCachedSwitch();
		} catch (Exception e) {
			updater.close();
			release.run();
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		}
	}
}
