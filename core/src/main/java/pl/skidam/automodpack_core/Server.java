package pl.skidam.automodpack_core;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

public class Server {

	// TODO Finish this class that it will be able to host the server without mod
	public static void main(String[] args) throws IOException {

		if (args.length < 1) {
			LOGGER.error("Modpack directory not provided!");
			return;
		}

		NettyServer server = new NettyServer();
		hostServer = server;

		String modpackDirStr = args[0];

		Path cwd = Path.of(System.getProperty("user.dir"));
		Path modpackDir = cwd.resolve("modpacks").resolve(modpackDirStr);

		modpackDir.toFile().mkdirs();

		serverConfigFile = modpackDir.resolve("server-config.json");

		serverConfig = ConfigTools.readOrCreate(serverConfigFile, Jsons.ServerConfigFieldsV3.class, Jsons.ServerConfigFieldsV3::new);
		if (serverConfig == null) {
			LOGGER.error("Failed to load standalone host configuration");
			return;
		}
		// Standalone host serves only what is already in the modpack dir, so no group pulls in CWD files.
		if (serverConfig.groups != null) serverConfig.groups.values().stream().filter(Objects::nonNull).forEach(group -> group.syncedFiles = new HashSet<>());
		serverConfig.validateSecrets = false;
		ConfigTools.writeAtomic(serverConfigFile, serverConfig);

		if (serverConfig.bindPort == -1) {
			LOGGER.error("Host port not set in config!");
			return;
		}

		Path serverRoot = modpackDir.resolve(serverDir);
		modpackExecutor = new ModpackExecutor(modpackDir, modpackDir, serverRoot);
		var generation = modpackExecutor.publish();

		if (generation instanceof ModpackExecutor.Published || generation instanceof ModpackExecutor.NoChanges) {
			LOGGER.info("Modpack generation completed!");
		} else if (generation instanceof ModpackExecutor.PublishFailed failed) {
			LOGGER.error("Failed to generate modpack", failed.failure());
		} else {
			LOGGER.error("Failed to generate modpack: operation was rejected");
		}

		LOGGER.info("Starting server on port {}", serverConfig.bindPort);
		server.start();
		// wait for server to stop
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
