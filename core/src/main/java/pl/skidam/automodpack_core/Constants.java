package pl.skidam.automodpack_core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
	private Constants() {}
}
