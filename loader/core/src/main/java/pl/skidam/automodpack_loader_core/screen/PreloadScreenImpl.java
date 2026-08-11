package pl.skidam.automodpack_loader_core.screen;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class PreloadScreenImpl implements ScreenService {

	// We leave this all empty
	@Override
	public void download(DownloadManager downloadManager, String modpackName) {}

	@Override
	public void changelog(Object parent, Changelogs changelogs) {}

	@Override
	public void restart(UpdateType updateType, Changelogs changelogs) {}

	@Override
	public void completeWithoutRestart() {}

	@Override
	public void welcome(ModpackUpdater modpackUpdater) {}

	@Override
	public boolean preview(UpdatePreview preview, String modpackName, Runnable continueAction, Runnable cancelAction, boolean returnToSelection,
			Map<UpdatePlan.FileKey, List<String>> mainPageUrls) {
		return false;
	}

	@Override
	public void recovery(ModpackUpdater modpackUpdater, ModpackUpdater.RecoverySnapshot recoverySnapshot, String modpackName, Runnable closed) {}

	@Override
	public void history(GenerationHistoryIndex historyIndex, List<GenerationRecord> availableHistory, String modpackName,
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory, HistoricalCatalogueLoader catalogueLoader, Runnable closed) {}

	@Override
	public void failure(FailureRequest request) {}

	@Override
	public void title() {}

	@Override
	public void validation(Object parent, String fingerprint, Runnable validated, Runnable canceled) {}

	@Override
	public void waiting() {}

	@Override
	public Optional<String> getScreenString() {
		return Optional.empty();
	}

	@Override
	public Optional<Object> getScreen() {
		return Optional.empty();
	}
}
