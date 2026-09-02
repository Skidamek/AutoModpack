package pl.skidam.automodpack_loader_core;

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
		return ClientStorage.fromGameDirectory(GameDirectory.current());
	}

	public static UpdateTransactionExecutor executor() {
		ClientStorage storage = storage();
		return new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(storage, UpdateTransactionSupport::applyLauncherMetadata));
	}

	private static void applyLauncherMetadata(UpdateTransaction transaction, ModpackJsons.ModpackContentFields manifest) throws IOException {
		if (!transaction.restartReasons.contains(RestartReason.CHANGED_LOADER_VERSION)) return;
		if (Constants.clientConfig == null) {
			Constants.clientConfig = ConfigTools.read(storage().clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new IOException("Client config is missing while applying launcher metadata"));
		}
		if (Constants.LOADER == null) Constants.LOADER = manifest.loader;
		if (!LauncherVersionSwapper.requiresLoaderVersionSwap(manifest.loader, manifest.loaderVersion)) return;
		if (!LauncherVersionSwapper.swapLoaderVersion(manifest.loader, manifest.loaderVersion))
			throw new IOException("Planned launcher loader-version change is no longer applicable");
		if (LauncherVersionSwapper.requiresLoaderVersionSwap(manifest.loader, manifest.loaderVersion))
			throw new IOException("Planned launcher loader-version change did not converge");
	}
}
