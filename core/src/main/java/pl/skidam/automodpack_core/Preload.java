package pl.skidam.automodpack_core;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

import pl.skidam.automodpack_core.client.BootRecovery;
import pl.skidam.automodpack_core.client.ClientLaunch;
import pl.skidam.automodpack_core.client.SelfUpdater;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.SelfUpdateSwap;
import pl.skidam.automodpack_core.utils.*;

/**
 * The loader-side prelaunch: seed the process constants, recover the instance-level self-update, then hand the
 * client's boot recovery to {@link BootRecovery} and start the launch's update work. Everything after the constants
 * is either the adapter's one-line delegations or the update phase ({@link ClientLaunch}); the entrypoints construct
 * this and nothing else.
 */
public class Preload {
	private ClientStorage storage;
	private boolean trustedBootstrapApply;
	private boolean rolledBackStuckUpdate;

	public Preload(LoaderManagerService loaderManager, ModpackLoaderService modpackLoader) {
		try {
			long start = System.currentTimeMillis();
			LOGGER.info("Prelaunching AutoModpack...");
			initializeConstants(loaderManager, modpackLoader);
			recoverPendingSelfUpdate();
			if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT) {
				storage = ClientStorage.open(GameDirectory.current());
				BootRecovery.BootDecision decision = new BootRecovery(storage).recover();
				clientConfig = decision.clientConfig();
				trustedBootstrapApply = decision.trustedBootstrapApply();
				rolledBackStuckUpdate = decision.rolledBackStuckUpdate();
			} else {
				serverConfig = ConfigUtils.loadOrCreateServerConfig();
			}
			updateAll();
			LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	/** Both roles run their real jars from mods/, so the instance-level swap recovers before any role machinery wakes up. */
	private void recoverPendingSelfUpdate() {
		Path gameDirectory = GameDirectory.current();
		try {
			SelfUpdateSwap.recover(gameDirectory, DataRootResolver.resolve(gameDirectory));
		} catch (IOException e) {
			throw new IllegalStateException("Cannot recover the pending AutoModpack self-update", e);
		}
	}

	private void updateAll() {
		if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER) {
			SelfUpdater.update();
			return;
		}
		new ClientLaunch(storage, trustedBootstrapApply, rolledBackStuckUpdate).run();
	}

	private void initializeConstants(LoaderManagerService loaderManager, ModpackLoaderService modpackLoader) {
		preload = true;
		PRELOAD_TIME = System.currentTimeMillis();
		LOADER_MANAGER = loaderManager;
		MODPACK_LOADER = modpackLoader;
		MC_VERSION = LOADER_MANAGER.getModVersion("minecraft");
		LOADER_VERSION = LOADER_MANAGER.getLoaderVersion();
		LOADER = LOADER_MANAGER.getPlatformType().toString().toLowerCase(Locale.ROOT);
		THIS_MOD_JAR = JarUtils.getJarPath(this.getClass());
		AM_VERSION = FileInspection.getModVersion(THIS_MOD_JAR);
	}
}
