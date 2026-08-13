package pl.skidam.automodpack.client.ui;

import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;

/** Owns durable client preference changes requested by screens. */
final class ClientPreferences {
	private ClientPreferences() {}

	static void setMusicEnabled(boolean enabled) {
		clientConfig.playMusic = enabled;
		try {
			ConfigTools.writeAtomic(ClientStorage.open(GameDirectory.current()).clientConfigFile(), clientConfig);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save client configuration", e);
		}
	}
}
