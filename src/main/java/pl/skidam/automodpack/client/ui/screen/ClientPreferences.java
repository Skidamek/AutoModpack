package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;
import java.util.List;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;

/** Owns durable client preference changes requested by screens. */
final class ClientPreferences {
	private ClientPreferences() {}

	static void setMusicEnabled(boolean enabled) {
		save(clientConfig.withPlayMusic(enabled));
	}

	static void setPinnedModIds(List<String> pinnedModIds) {
		save(clientConfig.withPinnedModIds(pinnedModIds));
	}

	private static void save(ClientConfigJsons.ClientConfigFieldsV3 next) {
		clientConfig = next;
		try {
			ConfigTools.writeAtomic(ClientStorage.open(GameDirectory.current()).clientConfigFile(), clientConfig);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save client configuration", e);
		}
	}
}
