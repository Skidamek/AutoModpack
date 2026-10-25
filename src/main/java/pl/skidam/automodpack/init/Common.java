package pl.skidam.automodpack.init;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import pl.skidam.automodpack.loader.GameCall;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.auth.ProvisioningSecretStore;
import pl.skidam.automodpack_core.client.SessionNotices;
import pl.skidam.automodpack_core.Deferred;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.host.HostRole;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.storage.StorageLeftovers;
import pl.skidam.automodpack_core.storage.StoragePaths;

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
		prepareRuntime();
		try {
			hostRole = HostRole.open(LoaderManagerService.EnvironmentType.SERVER, GameDirectory.current(), modpackExecutor);
			hostRole.bind(hostServer);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to prepare the modpack host", e);
		}
	}

	private static void prepareRuntime() {
		if (serverRuntimePrepared) return;
		if (serverConfig == null) serverConfig = ConfigUtils.loadOrCreateServerConfig();

		StorageLeftovers.warnAbout(GameDirectory.current().resolve(StoragePaths.AUTOMODPACK_DIR));

		ProvisioningSecretStore.ensure();

		hostServer = new NettyServer();
		modpackExecutor = new ModpackExecutor();
		serverRuntimePrepared = true;
	}

	public static void init() {
		GAME_CALL = new GameCall();
		Deferred.shared().drain();
	}

	public static synchronized void afterSetupServer() {
		if (LOADER_MANAGER != null && LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT) {
			prepareClientHostBestEffort();
			return;
		}
		prepareServerRuntime();
		startHostWhenOneIsBound();
	}

	/** A host with no generation has nothing to start; the absence line from the generation attempt is the receipt. */
	private static void startHostWhenOneIsBound() {
		if (hostRole.absence() != null) return;
		hostServer.start();
	}

	/**
	 * A client host is a courtesy, not the world's purpose: a failure costs the hosting for this session and is
	 * announced to the player, never the world start. What it serves is the {@link HostRole}'s one decision.
	 * Once the runtime is built, its failure costs only serving: commands and the joiner handshake keep a stable
	 * target, and the next world start retries.
	 */
	private static synchronized void prepareClientHostBestEffort() {
		try {
			prepareRuntime();
			hostRole = HostRole.open(LoaderManagerService.EnvironmentType.CLIENT, GameDirectory.current(), modpackExecutor);
			hostRole.bind(hostServer);
			startHostWhenOneIsBound();
		} catch (Exception e) {
			try {
				if (hostServer != null) hostServer.stop();
			} catch (Exception stopFailure) {
				e.addSuppressed(stopFailure);
			}
			String reason = e.getMessage() == null ? e.toString() : e.getMessage();
			hostRole = HostRole.unavailable(reason);
			SessionNotices.post("AutoModpack hosting failed", reason);
			LOGGER.error("AutoModpack hosting is unavailable this session", e);
		}
	}

	public static synchronized void beforeShutdownServer() {
		if (!serverRuntimePrepared) return;
		hostServer.stop();
		modpackExecutor.stop();
		hostServer = null;
		modpackExecutor = null;
		hostRole = null;
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
