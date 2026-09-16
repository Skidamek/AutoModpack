package pl.skidam.automodpack_loader_core.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.Objects;
import java.util.Optional;

import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public final class ScreenManager {

	private static volatile ScreenService instance = new PreloadScreenImpl();

	public static void install(ScreenService screenService) {
		instance = Objects.requireNonNull(screenService, "screenService");
	}

	public void download(DownloadManager downloadManager, String modpackName) {
		instance.download(downloadManager, modpackName);
	}

	public void changelog(Object parent, Changelogs changelogs) {
		instance.changelog(parent, changelogs);
	}

	public void restart(UpdateType updateType, Changelogs changelogs) {
		instance.restart(updateType, changelogs);
	}

	public void completeWithoutRestart() {
		instance.completeWithoutRestart();
	}

	public void welcome(ModpackUpdater modpackUpdater) {
		instance.welcome(modpackUpdater);
	}

	public boolean preview(UpdatePreview preview, String modpackName, Runnable continueAction, Runnable cancelAction, boolean returnToSelection) {
		return instance.preview(preview, modpackName, continueAction, cancelAction, returnToSelection);
	}

	public void history(HistoryViewRequest request) {
		instance.history(Objects.requireNonNull(request, "history request"));
	}

	/** Logs and presents an operational failure exactly once through the installed screen adapter. */
	public void failure(FailureRequest request) {
		Objects.requireNonNull(request, "request");
		LOGGER.error("AutoModpack client failure [{}] while displaying {}", request.category().key(), request.messageKey(), request.cause());
		instance.failure(request);
	}

	public void title() {
		instance.title();
	}

	public void validation(Object parent, String fingerprint, Runnable validated, Runnable canceled) {
		instance.validation(parent, fingerprint, validated, canceled);
	}

	public void waiting() {
		instance.waiting();
	}

	public Optional<String> getScreenString() {
		return instance.getScreenString();
	}

	public Optional<Object> getScreen() {
		return instance.getScreen();
	}
}
