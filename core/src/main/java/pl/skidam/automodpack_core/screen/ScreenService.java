package pl.skidam.automodpack_core.screen;

import java.util.Optional;

import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.client.DownloadManager;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.UpdateType;
import pl.skidam.automodpack_core.update.UpdatePreview;

public interface ScreenService {

	default void download(DownloadManager downloadManager, String modpackName) {}

	default void changelog(Changelogs changelogs) {}

	default void restart(UpdateType updateType, Changelogs changelogs) {}

	default void completeWithoutRestart() {}

	default void welcome(ModpackUpdater modpackUpdater) {}

	default boolean preview(UpdatePreview preview, String modpackName, ModpackUpdater updater, Runnable continueAction, Runnable cancelAction) {
		return false;
	}

	default void history(HistoryViewRequest request) {}

	default void failure(FailureRequest request) {}

	/**
	 * Asks the player to verify a server certificate during a join. The join is already owned by this prompt, so
	 * backing out of it always lands on the multiplayer hub — never on the vanilla connecting screen the prompt
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

	default void waiting() {}

	/** Shows the preparing screen; {@code onCancel} runs when the player backs out with Esc. */
	default void waiting(Runnable onCancel) {
		waiting();
	}

	/**
	 * The screens the player interacted with in this flow are gone, e.g. a login torn down before its modpack sync;
	 * the remembered return target is dropped and later returns land on the neutral fallback until a new interactive
	 * screen shows.
	 */
	default void discardReturnTarget() {}

	default Optional<String> getScreenString() {
		return Optional.empty();
	}

	/** True when a Minecraft screen is showing; core does not hold the Screen type. */
	default boolean hasScreen() {
		return false;
	}

	/** Simple class name of the current screen, for logs. */
	default Optional<String> getScreenKind() {
		return Optional.empty();
	}
}
