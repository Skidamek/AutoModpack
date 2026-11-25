package pl.skidam.automodpack_core.client;

import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.screen.RestartWindowText;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.text.L10n;

/**
 * The preload restart window's text. Minecraft's own translations are not loaded yet, and the window runs in its own
 * process, so the language comes from the options file and every string is resolved before the window is spawned.
 */
final class PreloadText {
	private PreloadText() {}

	/** The window shown after an update applied before the game started. */
	static RestartWindowText restart(UpdateType updateType) {
		String language = language();
		return new RestartWindowText(L10n.get(language, "automodpack.preload.windowTitle"), L10n.get(language, "automodpack.restart.description"),
				L10n.get(language, "automodpack.preload.ok"), L10n.get(language, "automodpack.restart." + (updateType == null ? "update" : updateType)));
	}

	/** The window shown when a file stayed open and the update could not finish in this boot. */
	static RestartWindowText deferred(String held) {
		String language = language();
		String message = held == null || held.isBlank() ? L10n.get(language, "automodpack.preload.deferred") : L10n.get(language, "automodpack.preload.deferredWithFile", held);
		return new RestartWindowText(L10n.get(language, "automodpack.preload.windowTitle"), L10n.get(language, "automodpack.restart.description"),
				L10n.get(language, "automodpack.preload.ok"), message);
	}

	private static String language() {
		return L10n.languageFromOptions(GameDirectory.current().resolve(ModpackPathPolicy.MINECRAFT_OPTIONS_FILE));
	}
}
