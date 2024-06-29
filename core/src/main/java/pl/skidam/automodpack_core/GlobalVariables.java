package pl.skidam.automodpack_core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.NullLoaderManager;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_core.modpack.Modpack;
import pl.skidam.automodpack_core.netty.HttpServer;

import java.nio.file.Path;

public class GlobalVariables {
    public static final Logger LOGGER = LogManager.getLogger("AutoModpack");
    public static final String MOD_ID = "automodpack";
    public static Boolean DEBUG = false;
    public static Boolean preload;
    public static String MC_VERSION;
    public static String AM_VERSION;
    public static String LOADER_VERSION;
    public static String LOADER;
    public static LoaderService LOADER_MANAGER = new NullLoaderManager();
    public static Path MODS_DIR; // TODO make use of this, its useful for clients using non-standard mods dir
    public static Modpack modpack;
    public static HttpServer httpServer;
    public static Jsons.ServerConfigFields serverConfig;
    public static Jsons.ClientConfigFields clientConfig;
    public static final Path automodpackDir = Path.of("automodpack");
    public final static Path hostModpackDir = automodpackDir.resolve("host-modpack");
    // TODO More server modpacks
    // Main - required
    // Addons - optional addon packs
    // Switches - optional or required packs, chosen by the player, only one can be installed at a time
    public final static Path hostContentModpackDir = hostModpackDir.resolve("main");
    public final static Path hostModpackContentFile = hostModpackDir.resolve("automodpack-content.json");
    public static final Path serverConfigFile = automodpackDir.resolve("automodpack-server.json");

    // Client

    public static final Path clientConfigFile = automodpackDir.resolve("automodpack-client.json");
    public static final Path modpacksDir = automodpackDir.resolve("modpacks");

    public static Path selectedModpackDir;
    public static String selectedModpackLink;
}
