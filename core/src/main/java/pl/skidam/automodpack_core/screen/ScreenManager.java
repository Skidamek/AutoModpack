package pl.skidam.automodpack_core.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.awt.GraphicsEnvironment;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.client.DownloadManager;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.UpdateType;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.update.UpdatePreview;

public final class ScreenManager {

	private static volatile ScreenService instance = new ScreenService() {
	};

	private ScreenManager() {}

	public static void install(ScreenService screenService) {
		instance = Objects.requireNonNull(screenService, "screenService");
	}

	public static void download(DownloadManager downloadManager, String modpackName) {
		instance.download(downloadManager, modpackName);
	}

	public static void changelog(Object parent, Changelogs changelogs) {
		instance.changelog(parent, changelogs);
	}

	public static void restart(UpdateType updateType, Changelogs changelogs) {
		instance.restart(updateType, changelogs);
	}

	/** Preload restart UI: Minecraft screens are not installed yet, so this is the AWT adapter. */
	public static Semaphore preloadRestart(String message) {
		if (GraphicsEnvironment.isHeadless()) return null;
		return new Gui().open(message);
	}

	public static void completeWithoutRestart() {
		instance.completeWithoutRestart();
	}

	public static void welcome(ModpackUpdater modpackUpdater) {
		instance.welcome(modpackUpdater);
	}

	public static boolean preview(UpdatePreview preview, String modpackName, ModpackUpdater updater, Runnable continueAction, Runnable cancelAction) {
		return instance.preview(preview, modpackName, updater, continueAction, cancelAction);
	}

	public static void history(HistoryViewRequest request) {
		instance.history(Objects.requireNonNull(request, "history request"));
	}

	/** Logs and presents an operational failure exactly once through the installed screen adapter. */
	public static void failure(FailureRequest request) {
		Objects.requireNonNull(request, "request");
		if (CertificateTrustCancelledException.is(request.cause())) return;
		String previousScreen = getScreen().map(screen -> screen.getClass().getSimpleName()).orElse("none");
		LOGGER.error("AutoModpack client failure [{}] ({}) on {}", request.category().key(), request.messageKey(), previousScreen, request.cause());
		instance.failure(request);
	}

	public static void validation(String fingerprint, String origin, Runnable validated, Runnable canceled) {
		instance.validation(fingerprint, origin, validated, canceled);
	}

	public static void originChange(String modpackName, String approvedOrigins, String newOrigin, Runnable allowed, Runnable refused) {
		instance.originChange(modpackName, approvedOrigins, newOrigin, allowed, refused);
	}

	public static void detachedJoin(String modpackName, boolean headMatchesActive, Runnable continueJoin, Runnable syncNow) {
		instance.detachedJoin(modpackName, headMatchesActive, continueJoin, syncNow);
	}

	public static void waiting() {
		instance.waiting();
	}

	public static void waiting(Runnable onCancel) {
		instance.waiting(onCancel);
	}

	public static void discardReturnTarget() {
		instance.discardReturnTarget();
	}

	public static Optional<String> getScreenString() {
		return instance.getScreenString();
	}

	public static Optional<Object> getScreen() {
		return instance.getScreen();
	}
}
