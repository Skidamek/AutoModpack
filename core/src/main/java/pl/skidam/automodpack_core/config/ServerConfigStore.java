package pl.skidam.automodpack_core.config;

import pl.skidam.automodpack_core.config.ServerConfigJsons.ServerConfigFieldsV3;

/** The running server config document: installed by the hosting boot, replaced whole by a reload. */
public final class ServerConfigStore {
	private static volatile ServerConfigFieldsV3 current;

	private ServerConfigStore() {}

	/** The document this process hosts on; reading it where no server config exists is a bug, so it fails here. */
	public static ServerConfigFieldsV3 current() {
		ServerConfigFieldsV3 config = current;
		if (config == null) throw new IllegalStateException("No server config is loaded: this process is not hosting");
		return config;
	}

	/** The document, or null on a process that never loaded one; callers that run on both sides ask for this. */
	public static ServerConfigFieldsV3 installed() {
		return current;
	}

	public static ServerConfigFieldsV3 install(ServerConfigFieldsV3 config) {
		current = config;
		return config;
	}

	/** The document, loading it if this host started without the server boot branch. */
	public static ServerConfigFieldsV3 loadIfAbsent() {
		ServerConfigFieldsV3 config = current;
		return config != null ? config : install(ConfigUtils.loadOrCreateServerConfig());
	}
}
