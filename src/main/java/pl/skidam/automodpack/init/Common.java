package pl.skidam.automodpack.init;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import pl.skidam.automodpack.loader.GameCall;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.auth.ProvisioningSecretStore;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

import java.util.HashMap;
import java.util.Map;

import static pl.skidam.automodpack_core.Constants.*;

public class Common {

	public static Map<String, Boolean> players = new HashMap<>();
	public static MinecraftServer server = null;
	private static boolean serverRuntimePrepared;

	public static synchronized void serverInit() {
		prepareServerRuntime();
		ModPackets.registerS2CPackets();
	}

	private static void prepareServerRuntime() {
		if (serverRuntimePrepared) return;
		if (serverConfig == null) serverConfig = ConfigUtils.loadOrCreateServerConfig();

		ProvisioningSecretStore.ensure();

		hostServer = new NettyServer();
		modpackExecutor = new ModpackExecutor();
		serverRuntimePrepared = true;

		if (serverConfig.generateModpackOnStart) {
			LOGGER.info("Generating modpack...");
			long genStart = System.currentTimeMillis();
			var generation = modpackExecutor.publish();
			if (generation instanceof ModpackExecutor.Published || generation instanceof ModpackExecutor.NoChanges) {
				LOGGER.info("Modpack generation completed! took {}ms", System.currentTimeMillis() - genStart);
			} else if (generation instanceof ModpackExecutor.PublishFailed failed) {
				LOGGER.error("Failed to generate modpack", failed.failure());
			} else {
				LOGGER.error("Failed to generate modpack: operation was rejected");
			}
		} else {
			LOGGER.info("Loading last modpack...");
			long genStart = System.currentTimeMillis();
			var generation = modpackExecutor.loadLast();
			if (generation instanceof ModpackExecutor.Loaded loaded) {
				LOGGER.info("Modpack loaded at generation {}! took {}ms", loaded.current().metadata().generationId(), System.currentTimeMillis() - genStart);
			} else if (generation instanceof ModpackExecutor.LoadFailed failed) {
				LOGGER.error("Failed to load modpack", failed.failure());
			} else {
				LOGGER.error("Failed to load modpack: operation was rejected");
			}
		}
	}

	public static void init() {
		GAME_CALL = new GameCall();
	}

	public static synchronized void afterSetupServer() {
		prepareServerRuntime();
		hostServer.start();
	}

	public static synchronized void beforeShutdownServer() {
		if (!serverRuntimePrepared) return;

		hostServer.stop();
		modpackExecutor.stop();
		hostServer = null;
		modpackExecutor = null;
		serverRuntimePrepared = false;
	}

	// <1.19.2 has no Identifier factory, only the deprecated-for-removal
	// two-arg constructor, so suppress the unavoidable removal warning there.
	@SuppressWarnings("removal")
	public static Identifier id(String path) {
		/*? if >=1.21.11 {*/
		return Identifier.tryBuild(MOD_ID, path);
		/*?} else if >=1.19.2 {*/
		/*return Identifier.tryBuild(MOD_ID, path);
		*//*?} else {*/
		/*return new Identifier(MOD_ID, path);
		*//*?}*/
	}

	@SuppressWarnings("removal")
	public static Identifier resourceId(String resourceLocation) {
		int separator = resourceLocation.indexOf(':');
		String namespace = resourceLocation.substring(0, separator);
		String path = resourceLocation.substring(separator + 1);
		/*? if >=1.21.11 {*/
		return Identifier.tryBuild(namespace, path);
		/*?} else if >=1.19.2 {*/
		/*return Identifier.tryBuild(namespace, path);
		*//*?} else {*/
		/*return new Identifier(namespace, path);
		*//*?}*/
	}
}
