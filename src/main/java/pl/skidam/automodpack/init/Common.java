package pl.skidam.automodpack.init;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import pl.skidam.automodpack.loader.GameCall;
import pl.skidam.automodpack.networking.ModPackets;
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

		hostServer = new NettyServer();
		modpackExecutor = new ModpackExecutor();
		serverRuntimePrepared = true;

		if (serverConfig.generateModpackOnStart) {
			LOGGER.info("Generating modpack...");
			long genStart = System.currentTimeMillis();
			var generation = modpackExecutor.generateNew();
			if (generation.succeeded()) {
				LOGGER.info("Modpack generation {}! took {}ms", generation.status(), System.currentTimeMillis() - genStart);
			} else {
				LOGGER.error("Failed to generate modpack!", generation.failure());
			}
		} else {
			LOGGER.info("Loading last modpack...");
			long genStart = System.currentTimeMillis();
			var generation = modpackExecutor.loadLast();
			if (generation.succeeded()) {
				LOGGER.info("Modpack loaded at generation {}! took {}ms", generation.current().metadata().generationId(), System.currentTimeMillis() - genStart);
			} else {
				LOGGER.error("Failed to load modpack!", generation.failure());
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
}
