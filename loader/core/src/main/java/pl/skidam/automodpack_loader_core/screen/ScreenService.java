package pl.skidam.automodpack_loader_core.screen;

import java.util.Optional;

import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public interface ScreenService {

	void download(DownloadManager downloadManager, String modpackName);

	void changelog(Object parent, Changelogs changelogs);

	void restart(UpdateType updateType, Changelogs changelogs);

	void completeWithoutRestart();

	void welcome(ModpackUpdater modpackUpdater);

	boolean preview(UpdatePreview preview, String modpackName, Runnable continueAction, Runnable cancelAction, boolean returnToSelection);

	void history(HistoryViewRequest request);

	void failure(FailureRequest request);

	void title();

	void validation(Object parent, String fingerprint, Runnable validated, Runnable canceled);

	void waiting();

	Optional<String> getScreenString();

	Optional<Object> getScreen();
}
