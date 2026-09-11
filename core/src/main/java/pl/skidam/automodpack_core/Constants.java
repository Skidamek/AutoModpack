package pl.skidam.automodpack_core;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.loader.GameCallService;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.loader.NullGameCall;
import pl.skidam.automodpack_core.loader.NullLoaderManager;
import pl.skidam.automodpack_core.loader.NullModpackLoader;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

/** Process-wide runtime state retained for loader compatibility. */
public final class Constants {
	public static final Logger LOGGER = LogManager.getLogger("AutoModpack");
	public static final String MOD_ID = "automodpack"; // For real its "automodpack_mod" but we use this for resource locations etc.
	public static Boolean DEBUG = false;
	public static Boolean preload;
	public static long PRELOAD_TIME;
	public static String MC_VERSION;
	public static String AM_VERSION;
	public static String LOADER_VERSION;
	public static String LOADER;
	public static LoaderManagerService LOADER_MANAGER = new NullLoaderManager();
	public static ModpackLoaderService MODPACK_LOADER = new NullModpackLoader();
	public static GameCallService GAME_CALL = new NullGameCall();
	public static Path THIS_MOD_JAR;
	public static ModpackExecutor modpackExecutor;
	public static NettyServer hostServer;
	public static ServerConfigJsons.ServerConfigFieldsV3 serverConfig;
	public static ClientConfigJsons.ClientConfigFieldsV3 clientConfig;
	private Constants() {}
}
