package pl.skidam.automodpack_core.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.client.UpdateType;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.utils.Throwables;

public final class ScreenManager {

	private static volatile ScreenService instance = new ScreenService() {
	};

	private ScreenManager() {}

	public static void install(ScreenService screenService) {
		instance = Objects.requireNonNull(screenService, "screenService");
	}

	public static void download(DownloadView download, String modpackName) {
		instance.download(download, modpackName);
	}

	public static void download(DownloadView download, String modpackName, Runnable onCancel) {
		instance.download(download, modpackName, onCancel);
	}

	public static void changelog(Changelogs changelogs) {
		instance.changelog(changelogs);
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

	public static void musicReady(Path track) {
		instance.musicReady(track);
	}

	public static void welcome(ReviewPayload payload) {
		instance.welcome(payload);
	}

	public static boolean preview(PreviewPayload payload) {
		return instance.preview(payload);
	}

	public static void history(HistoryViewRequest request) {
		instance.history(Objects.requireNonNull(request, "history request"));
	}

	/** Logs and presents an operational failure exactly once through the installed screen adapter. */
	public static void failure(FailureRequest request) {
		Objects.requireNonNull(request, "request");
		if (CertificateTrustCancelledException.is(request.cause())) return;
		if (Throwables.findCause(request.cause(), InterruptedException.class) != null) return;
		String previousScreen = getScreenKind().orElse("none");
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

	public static void modpackOffer(Runnable syncModpack, Runnable joinWithout, Runnable cancel) {
		instance.modpackOffer(syncModpack, joinWithout, cancel);
	}

	public static void waiting() {
		instance.waiting();
	}

	/** Runs a task on the client thread; headless adapters run it inline. */
	public static void clientThread(Runnable task) {
		instance.clientThread(task);
	}

	/** Runs a task off the player thread and answers with its handle; the headless adapter runs it inline. */
	public static Future<?> background(Runnable task) {
		return instance.background(task);
	}

	public static void waiting(Runnable onCancel) {
		instance.waiting(onCancel);
	}

	public static void restore() {
		instance.restore();
	}

	public static void discardReturnTarget() {
		instance.discardReturnTarget();
	}

	public static boolean hasScreen() {
		return instance.hasScreen();
	}

	public static Optional<String> getScreenKind() {
		return instance.getScreenKind();
	}
}
