package pl.skidam.automodpack_core;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_CONFIG_FILE;

import java.io.IOException;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

public class Server {

	// Standalone hosting uses the same instance layout as the modded server.
	public static void main(String[] args) throws IOException {

		NettyServer server = new NettyServer();
		hostServer = server;

		serverConfig = ConfigTools.readOrCreate(SERVER_CONFIG_FILE, ServerConfigJsons.ServerConfigFieldsV3.class, ServerConfigJsons::standalone);
		if (serverConfig == null) {
			LOGGER.error("Failed to load standalone host configuration");
			return;
		}

		if (serverConfig.bindPort == -1) {
			LOGGER.error("Host port not set in config!");
			return;
		}

		modpackExecutor = new ModpackExecutor();
		var generation = modpackExecutor.publish();

		if (generation instanceof ModpackExecutor.Published || generation instanceof ModpackExecutor.NoChanges) {
			LOGGER.info("Modpack generation completed!");
		} else if (generation instanceof ModpackExecutor.PublishResult.Rejected rejected) {
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
