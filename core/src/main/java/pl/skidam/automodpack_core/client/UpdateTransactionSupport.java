package pl.skidam.automodpack_core.client;

import java.io.IOException;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.launchers.LauncherVersionSwapper;

public final class UpdateTransactionSupport {
	private UpdateTransactionSupport() {}

	public static ClientStorage storage() {
		return ClientStorage.open(GameDirectory.current());
	}

	/** The executor for a booted client or server process, whose Constants carry the client session state. */
	public static UpdateTransactionExecutor executor() {
		return executor(Constants.clientConfig, Constants.LOADER);
	}

	/**
	 * The executor with explicit client session state. The helper process has no boot and passes nulls: the launcher
	 * metadata step then reads the config from storage and stands in the manifest's own loader for the client's.
	 */
	public static UpdateTransactionExecutor executor(ClientConfigJsons.ClientConfigFieldsV3 clientConfig, String clientLoader) {
		ClientStorage storage = storage();
		return new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(storage, (transaction, manifest) -> applyLauncherMetadata(clientConfig, clientLoader, storage, transaction, manifest)));
	}

	private static void applyLauncherMetadata(ClientConfigJsons.ClientConfigFieldsV3 clientConfig, String clientLoader, ClientStorage storage, UpdateTransaction transaction,
			ModpackJsons.ModpackContentFields manifest) throws IOException {
		if (!transaction.plan().restartReasons().contains(RestartReason.CHANGED_LOADER_VERSION)) return;
		ClientConfigJsons.ClientConfigFieldsV3 config = clientConfig != null ? clientConfig : readClientConfig(storage);
		// Without a seeded client loader the manifest's own loader is the only stand-in, so the type check degenerates and the version decides.
		String loader = clientLoader != null ? clientLoader : manifest.loader;
		if (!LauncherVersionSwapper.requiresLoaderVersionSwap(manifest.loader, manifest.loaderVersion, config.syncLoaderVersion, loader)) return;
		if (!LauncherVersionSwapper.swapLoaderVersion(manifest.loader, manifest.loaderVersion, config.syncLoaderVersion, loader))
			throw new IOException("Planned launcher loader-version change is no longer applicable");
		if (LauncherVersionSwapper.requiresLoaderVersionSwap(manifest.loader, manifest.loaderVersion, config.syncLoaderVersion, loader))
			throw new IOException("Planned launcher loader-version change did not converge");
	}

	private static ClientConfigJsons.ClientConfigFieldsV3 readClientConfig(ClientStorage storage) throws IOException {
		return ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseThrow(() -> new IOException("Client config is missing while applying launcher metadata"));
	}
}
