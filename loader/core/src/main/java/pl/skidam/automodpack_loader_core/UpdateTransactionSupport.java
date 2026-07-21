package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Path;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.launchers.LauncherVersionSwapper;

public final class UpdateTransactionSupport {
	private UpdateTransactionSupport() {}

	public static UpdateTransactionExecutor executor(UpdateTransaction transaction) throws IOException {
		Path gameDirectory = SmartFileUtils.CWD.toAbsolutePath().normalize();
		Path modpackDirectory = null;
		Path installedManifest = null;
		UpdateTransactionExecutor.CommitAction beforeManifest = null;
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			try {
				modpackDirectory = Path.of(transaction.canonicalModpackDirectory).toAbsolutePath().normalize();
			} catch (RuntimeException e) {
				throw new IOException("Invalid canonical modpack directory", e);
			}
			installedManifest = modpackDirectory.resolve(hostModpackContentFile.getFileName());
			beforeManifest = UpdateTransactionSupport::applyLauncherMetadata;
		}
		return new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(gameDirectory, modpackDirectory, gameDirectory.resolve("mods"),
				gameDirectory.resolve(storeDir), gameDirectory.resolve(automodpackDir), gameDirectory.resolve(transactionFile), gameDirectory.resolve(transactionResultFile),
				gameDirectory.resolve(clientConfigFile), gameDirectory.resolve(clientDeletionTimeStamps), installedManifest, beforeManifest));
	}

	private static void applyLauncherMetadata(UpdateTransaction transaction) throws IOException {
		if (!transaction.restartReasons.contains(RestartReason.CHANGED_LOADER_VERSION)) return;
		Jsons.ModpackContentFields manifest = transaction.targetManifest();
		if (clientConfig == null) {
			clientConfig = ConfigTools.read(SmartFileUtils.CWD.resolve(clientConfigFile), Jsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new IOException("Client config is missing while applying launcher metadata"));
		}
		if (LOADER == null) LOADER = manifest.loader;
		if (!LauncherVersionSwapper.requiresLoaderVersionSwap(manifest.loader, manifest.loaderVersion)) return;
		if (!LauncherVersionSwapper.swapLoaderVersion(manifest.loader, manifest.loaderVersion))
			throw new IOException("Planned launcher loader-version change is no longer applicable");
		if (LauncherVersionSwapper.requiresLoaderVersionSwap(manifest.loader, manifest.loaderVersion))
			throw new IOException("Planned launcher loader-version change did not converge");
	}
}
