package pl.skidam.automodpack.init;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import pl.skidam.automodpack.loader.GameCall;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.auth.ProvisioningSecretStore;
import pl.skidam.automodpack_core.client.ClientHostState;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;

import java.io.IOException;
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

		runGeneration();
	}

	private static void runGeneration() {
		if (serverConfig.generateModpackOnStart) {
			LOGGER.info("Generating modpack...");
			long genStart = System.currentTimeMillis();
			var generation = modpackExecutor.publish();
			if (generation instanceof ModpackExecutor.Published || generation instanceof ModpackExecutor.NoChanges) {
				LOGGER.info("Modpack generation completed! took {}ms", System.currentTimeMillis() - genStart);
			} else if (generation instanceof ModpackExecutor.PublishResult.Rejected rejected) {
				throw new IllegalStateException("Failed to generate modpack: " + rejected.detail(), rejected.cause());
			}
		} else {
			LOGGER.info("Loading last modpack...");
			long genStart = System.currentTimeMillis();
			var generation = modpackExecutor.loadLast();
			if (generation instanceof ModpackExecutor.Loaded loaded) {
				LOGGER.info("Modpack loaded at content {}! took {}ms", loaded.current().contentToken(), System.currentTimeMillis() - genStart);
			} else if (generation instanceof ModpackExecutor.LoadResult.Rejected rejected) {
				throw new IllegalStateException("Failed to load modpack: " + rejected.detail(), rejected.cause());
			}
		}
	}

	public static void init() {
		GAME_CALL = new GameCall();
	}

	public static synchronized void afterSetupServer() {
		if (LOADER_MANAGER != null && LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT) {
			prepareClientHostBestEffort();
			return;
		}
		prepareServerRuntime();
		hostServer.start();
	}

	/**
	 * A client host is a courtesy, not the world's purpose: a failure costs the hosting for this session and is
	 * announced to the player, never the world start. When the client runs a downloaded pack, that pack is hosted
	 * byte-exactly from the client's own store; without one the game directory generates a pack like a server's.
	 */
	private static synchronized void prepareClientHostBestEffort() {
		try {
			prepareClientHostRuntime();
			hostServer.start();
		} catch (Exception e) {
			// The runtime stays constructed: commands and the joiner handshake keep a stable target, only serving is
			// down. The next world start retries through the same path.
			try {
				if (hostServer != null) hostServer.stop();
			} catch (Exception stopFailure) {
				e.addSuppressed(stopFailure);
			}
			String reason = e.getMessage() == null ? e.toString() : e.getMessage();
			ClientHostState.hostingFailed(reason);
			LOGGER.error("AutoModpack hosting is unavailable this session", e);
		}
	}

	private static void prepareClientHostRuntime() throws IOException {
		if (serverRuntimePrepared) return;
		if (serverConfig == null) serverConfig = ConfigUtils.loadOrCreateServerConfig();

		ProvisioningSecretStore.ensure();

		hostServer = new NettyServer();
		modpackExecutor = new ModpackExecutor();
		serverRuntimePrepared = true;

		ClientStorage storage = ClientStorage.open(GameDirectory.current());
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		if (generations.activeDocument().isPresent()) {
			hostServer.replacePaths(generations.hosting());
			LOGGER.info("Hosting this client's active modpack to joining players");
			return;
		}
		runGeneration();
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
}
