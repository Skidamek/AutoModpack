package pl.skidam.automodpack_loader_core.screen;

import java.util.Optional;

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
	public boolean preview(UpdatePreview preview, String modpackName, ModpackUpdater updater, Runnable continueAction, Runnable cancelAction) {
		return false;
	}

	@Override
	public void history(HistoryViewRequest request) {}

	@Override
	public void failure(FailureRequest request) {}

	@Override
	public void validation(Object parent, String fingerprint, String origin, Runnable validated, Runnable canceled) {}

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
