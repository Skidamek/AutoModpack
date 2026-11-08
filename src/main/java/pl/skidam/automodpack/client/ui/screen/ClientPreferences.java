package pl.skidam.automodpack.client.ui.screen;

import java.util.List;

import pl.skidam.automodpack_core.update.ClientStorage;

/** Owns durable client preference changes requested by screens. */
final class ClientPreferences {
	private ClientPreferences() {}

	static void setMusicEnabled(boolean enabled) {
		ClientStorage.current().clientConfig().commit(current -> current.withPlayMusic(enabled));
	}

	static void setPinnedModIds(List<String> pinnedModIds) {
		ClientStorage.current().clientConfig().commit(current -> current.withPinnedModIds(pinnedModIds));
	}
}
