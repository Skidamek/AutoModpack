package pl.skidam.automodpack_core.screen;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.client.UpdateType;

public interface ScreenService {

	default void download(DownloadView download, String modpackName) {}

	/** Shows the download screen; {@code onCancel} is the same player-cancel seam the preparing screen uses. */
	default void download(DownloadView download, String modpackName, Runnable onCancel) {
		download(download, modpackName);
	}

	default void changelog(Changelogs changelogs) {}

	default void restart(UpdateType updateType, Changelogs changelogs) {}

	default void completeWithoutRestart() {}

	default void welcome(ReviewPayload payload) {}

	default boolean preview(PreviewPayload payload) {
		return false;
	}

	default void history(HistoryViewRequest request) {}

	default void failure(FailureRequest request) {}

	/**
	 * Asks the player to verify a server certificate during a join. The join is already owned by this prompt, so
	 * backing out of it always lands on the multiplayer hub - never on the vanilla connecting screen the prompt
	 * interrupted, which has no live connection to return to.
	 */
	default void validation(String fingerprint, String origin, Runnable validated, Runnable canceled) {}

	/** Asks before an installed modpack starts being served from a different address; exactly one of the runnables runs. */
	default void originChange(String modpackName, String approvedOrigins, String newOrigin, Runnable allowed, Runnable refused) {
		refused.run();
	}

	/**
	 * Warn-but-allow prompt for a pack running detached from its server; exactly one of the runnables runs. The prompt
	 * shows on every detached join, and {@code headMatchesActive} only picks the body paragraph: equal tokens say
	 * nothing about locally changed files. The default continues the join headlessly, keeping the local sovereignty
	 * the detached state promises.
	 */
	default void detachedJoin(String modpackName, boolean headMatchesActive, Runnable continueJoin, Runnable syncNow) {
		continueJoin.run();
	}

	/**
	 * Offers the server's optional modpack before any sync transport opens; exactly one of the runnables runs. No
	 * modpack name is known yet, since nothing was fetched. The default joins without the pack headlessly, keeping the
	 * no-pack join an optional offer promises.
	 */
	default void modpackOffer(Runnable syncModpack, Runnable joinWithout, Runnable cancel) {
		joinWithout.run();
	}

	default void waiting() {}

	/** Runs a task on the client thread; headless adapters run it inline. */
	default void clientThread(Runnable task) {
		task.run();
	}

	/**
	 * Runs a task off the player thread and answers with its handle, so storage and network work never block the client
	 * thread and screens can cancel abandoned work. The headless adapter runs the task inline, keeping core tests
	 * deterministic.
	 */
	default Future<?> background(Runnable task) {
		task.run();
		return CompletableFuture.completedFuture(null);
	}

	/** Shows the preparing screen; {@code onCancel} runs when the player backs out with Esc. */
	default void waiting(Runnable onCancel) {
		waiting();
	}

	/**
	 * Ends the current wait/download episode and returns to the remembered parent. No-op when a successor already
	 * claimed the episode (restart, welcome, preview, failure) or no wait is showing - including when that successor
	 * is still waiting on the loading dwell. A wait cannot outlive the engine: {@code ModpackUpdater.close()} always
	 * calls this.
	 */
	default void restore() {}

	/**
	 * The screens the player interacted with in this flow are gone, e.g. a login torn down before its modpack sync;
	 * the remembered return target is dropped and later returns land on the neutral fallback until a new interactive
	 * screen shows.
	 */
	default void discardReturnTarget() {}

	/** True when a Minecraft screen is showing; core does not hold the Screen type. */
	default boolean hasScreen() {
		return false;
	}

	/** Simple class name of the current screen, for logs. */
	default Optional<String> getScreenKind() {
		return Optional.empty();
	}
}
