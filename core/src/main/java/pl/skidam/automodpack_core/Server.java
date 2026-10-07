package pl.skidam.automodpack_core;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_CONFIG_FILE;

import java.io.IOException;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ReconfConfigs;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.storage.StorageLeftovers;
import pl.skidam.automodpack_core.storage.StoragePaths;

public class Server {

	// Standalone hosting uses the same instance layout as the modded server.
	public static void main(String[] args) throws IOException {

		NettyServer server = new NettyServer();
		hostServer = server;

		try {
			serverConfig = ReconfConfigs.readOrCreate(SERVER_CONFIG_FILE, ServerConfigJsons.ServerConfigFieldsV3.class, ServerConfigJsons::standalone);
		} catch (ConfigTools.ConfigException e) {
			LOGGER.error("Failed to load standalone host configuration: {}", e.getMessage());
			return;
		}

		StorageLeftovers.warnAbout(GameDirectory.current().resolve(StoragePaths.AUTOMODPACK_DIR));

		if (serverConfig.bindPort == -1) {
			LOGGER.error("Host port not set in config!");
			return;
		}

		modpackExecutor = new ModpackExecutor();
		var generation = modpackExecutor.publish();

		if (generation instanceof ModpackExecutor.Published || generation instanceof ModpackExecutor.NoChanges) {
			LOGGER.info("Modpack generation completed!");
		} else if (generation instanceof ModpackExecutor.PublishResult.NothingToPublish nothing) {
			// A host with nothing to host has no world to keep running for, and exiting 0 reads as a clean shutdown to
			// whatever supervises it. Say why and leave non-zero.
			LOGGER.error("This host has no modpack to serve: {}", nothing.absence().detail());
			System.exit(1);
		} else if (generation instanceof ModpackExecutor.Rejected rejected) {
			LOGGER.error("Failed to generate modpack: {}", rejected.detail(), rejected.cause());
		}

		LOGGER.info("Starting server on port {}", serverConfig.bindPort);
		server.start();
		while (server.isRunning()) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				LOGGER.error("Interrupted server thread", e);
			}
		}
		modpackExecutor.stop();
	}
}
