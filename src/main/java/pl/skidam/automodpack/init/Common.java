package pl.skidam.automodpack.init;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import pl.skidam.automodpack.loader.GameCall;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.AutoModpack;
import pl.skidam.automodpack_core.Deferred;
import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.Hosting;
import pl.skidam.automodpack_core.auth.ProvisioningSecretStore;
import pl.skidam.automodpack_core.client.SessionNotices;
import pl.skidam.automodpack_core.config.ServerConfigStore;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.LoaderServices;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.host.HostRole;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.storage.StorageLeftovers;
import pl.skidam.automodpack_core.storage.StoragePaths;


public class Common {
	private static final Logger LOGGER = LogManager.getLogger(Common.class);


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
			HostRole role = HostRole.open(LoaderManagerService.EnvironmentType.SERVER, GameDirectory.current(), Hosting.executor());
			role.bind(Hosting.server());
			Hosting.installRole(role);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to prepare the modpack host", e);
		}
	}

	private static void prepareRuntime() {
		if (serverRuntimePrepared) return;
		ServerConfigStore.loadIfAbsent();

		StorageLeftovers.warnAbout(GameDirectory.current().resolve(StoragePaths.AUTOMODPACK_DIR));

		ProvisioningSecretStore.ensure();

		Hosting.installServer(new NettyServer());
		LoaderManagerService.EnvironmentType environment = Environment.sideOrNull();
		Hosting.installExecutor(new ModpackExecutor(GameDirectory.current(), HostRole.ownedElsewhere(environment, GameDirectory.current())));
		serverRuntimePrepared = true;
	}

	public static void init() {
		LoaderServices.installGameCall(new GameCall());
		Deferred.shared().drain();
	}

	public static synchronized void afterSetupServer() {
		if (Environment.installed() != null && Environment.current().isClient()) {
			prepareClientHostBestEffort();
			return;
		}
		prepareServerRuntime();
		startHostWhenOneIsBound();
	}

	/** A host with no generation has nothing to start; the absence line from the generation attempt is the receipt. */
	private static void startHostWhenOneIsBound() {
		if (Hosting.role().absence() != null) return;
		Hosting.server().start();
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
			HostRole role = HostRole.open(LoaderManagerService.EnvironmentType.CLIENT, GameDirectory.current(), Hosting.executor());
			role.bind(Hosting.server());
			Hosting.installRole(role);
			startHostWhenOneIsBound();
		} catch (Exception e) {
			try {
				NettyServer server = Hosting.serverOrNull();
				if (server != null) server.stop();
			} catch (Exception stopFailure) {
				e.addSuppressed(stopFailure);
			}
			String reason = e.getMessage() == null ? e.toString() : e.getMessage();
			Hosting.installRole(HostRole.unavailable(reason));
			SessionNotices.post("AutoModpack hosting failed", reason);
			LOGGER.error("AutoModpack hosting is unavailable this session", e);
		}
	}

	public static synchronized void beforeShutdownServer() {
		if (!serverRuntimePrepared) return;
		Hosting.server().stop();
		Hosting.executor().stop();
		Hosting.clear();
		serverRuntimePrepared = false;
	}

	// <1.19.2 has no Identifier factory, only the deprecated-for-removal
	// two-arg constructor, so suppress the unavoidable removal warning there.
	@SuppressWarnings("removal")
	public static Identifier id(String path) {
		/*? if >=1.21.11 {*/
		return Identifier.tryBuild(AutoModpack.ID, path);
		/*?} else if >=1.19.2 {*/
		/*return Identifier.tryBuild(AutoModpack.ID, path);
		*//*?} else {*/
		/*return new Identifier(AutoModpack.ID, path);
		*//*?}*/
	}
}
