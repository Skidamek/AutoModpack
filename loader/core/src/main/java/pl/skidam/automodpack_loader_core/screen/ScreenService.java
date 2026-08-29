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

	boolean preview(UpdatePreview preview, String modpackName, ModpackUpdater updater, Runnable continueAction, Runnable cancelAction);

	void history(HistoryViewRequest request);

	void failure(FailureRequest request);

	void validation(Object parent, String fingerprint, String origin, Runnable validated, Runnable canceled);

	void waiting();

	/** Shows the preparing screen; {@code onCancel} runs when the player backs out with Esc. */
	default void waiting(Runnable onCancel) {
		waiting();
	}

	Optional<String> getScreenString();

	Optional<Object> getScreen();
}
