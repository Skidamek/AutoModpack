package pl.skidam.automodpack_core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.loader.GameCallService;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.loader.NullGameCall;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.host.HostRole;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

/** Process-wide runtime state retained for loader compatibility. */
public final class Constants {
	public static final Logger LOGGER = LogManager.getLogger("AutoModpack");
	public static final String MOD_ID = "automodpack"; // For real its "automodpack_mod" but we use this for resource locations etc.
	public static ModpackLoaderService MODPACK_LOADER;
	public static GameCallService GAME_CALL = new NullGameCall();
	public static ModpackExecutor modpackExecutor;
	public static NettyServer hostServer;
	public static volatile HostRole hostRole;
	public static volatile ServerConfigJsons.ServerConfigFieldsV3 serverConfig;
	/** The process config document: boot owns loading it, flows land committed snapshots through one seam, screens only read. */
	public static volatile ClientConfigJsons.ClientConfigFieldsV3 clientConfig;
	/**
	 * Serializes every read-modify-write of {@link #clientConfig} between flow commits and preference saves: the
	 * monitor covers deriving from the current document, landing it on the static, and writing it durably, so a
	 * landing can never silently discard a concurrent one. Boot writes stay outside - they run single-threaded
	 * before any flow or screen exists.
	 */
	public static final Object clientConfigWriteLock = new Object();
	private Constants() {}
}
