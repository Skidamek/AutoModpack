package pl.skidam.automodpack_core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.loader.NullLoaderManager;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.NullModpackLoader;
import pl.skidam.automodpack_core.modpack.Modpack;
import pl.skidam.automodpack_core.netty.HttpServer;

import java.nio.file.Path;

public class GlobalVariables {
    public static final Logger LOGGER = LogManager.getLogger("AutoModpack");
    public static final String MOD_ID = "automodpack";
    public static final String SECRET_REQUEST_HEADER = "AutoModpack-Secret";
    public static Boolean DEBUG = false;
    public static Boolean preload;
    public static String MC_VERSION;
    public static String AM_VERSION;
    public static String LOADER_VERSION;
    public static String LOADER;
    public static LoaderManagerService LOADER_MANAGER = new NullLoaderManager();
    public static ModpackLoaderService MODPACK_LOADER = new NullModpackLoader();
    public static Path AUTOMODPACK_JAR;
    public static Path MODS_DIR;
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
    public static Path hostModpackContentFile = hostModpackDir.resolve("automodpack-content.json");
    public static Path hostSecretsFile = hostModpackDir.resolve("automodpack-secrets.json");
    public static Path serverConfigFile = automodpackDir.resolve("automodpack-server.json");
    public static Path serverCoreConfigFile = automodpackDir.resolve("automodpack-core.json");

    // Client
    public static final Path clientConfigFile = automodpackDir.resolve("automodpack-client.json");
    public static final Path clientSecretsFile = automodpackDir.resolve("automodpack-secrets.json");
    public static final Path modpacksDir = automodpackDir.resolve("modpacks");

    public static final String clientConfigFileOverrideResource = "overrides-automodpack-client.json";
    public static String clientConfigOverride; // read from inside a jar file on preload, used instead of clientConfigFile if exists

    public static Path selectedModpackDir;
}
