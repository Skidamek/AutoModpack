package pl.skidam.automodpack_core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.*;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.FullServerPack;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

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
    public static LoaderManagerService LOADER_MANAGER = new NullLoaderManager();
    public static ModpackLoaderService MODPACK_LOADER = new NullModpackLoader();
    public static GameCallService GAME_CALL = new NullGameCall();
    public static Path THIZ_JAR;
    public static Path MODS_DIR;
    // new modpack class, now gen about the Executor
    public static ModpackExecutor modpackExecutor;
    public static FullServerPack fullpacks;
    public static NettyServer hostServer;
    public static Jsons.ServerConfigFields serverConfig;
    public static Jsons.ClientConfigFieldsV2 clientConfig;
    public static Jsons.KnownHostsFields knownHosts;
    public static final Path automodpackDir = Path.of("automodpack");
    public static final Path hostModpackDir = automodpackDir.resolve("host-modpack");
    // TODO More server modpacks
    // Main - required
    // Addons - optional addon packs
    // Switches - optional or required packs, chosen by the player, only one can be installed at a time
    public static final Path hostContentModpackDir = hostModpackDir.resolve("main");
    public static Path hostModpackContentFile = hostModpackDir.resolve("automodpack-content.json");
    public static Path serverConfigFile = automodpackDir.resolve("automodpack-server.json");
    public static Path serverCoreConfigFile = automodpackDir.resolve("automodpack-core.json");
    public static final Path privateDir = automodpackDir.resolve(".private");
    public static final Path serverSecretsFile = privateDir.resolve("automodpack-secrets.json");
    public static final Path knownHostsFile = privateDir.resolve("automodpack-known-hosts.json");
    public static final Path serverCertFile = privateDir.resolve("cert.crt");
    public static final Path serverPrivateKeyFile = privateDir.resolve("key.pem");


    // Client
    public static final Path modpackContentTempFile = automodpackDir.resolve("automodpack-content.json.temp");
    public static final Path clientConfigFile = automodpackDir.resolve("automodpack-client.json");
    public static final Path clientSecretsFile = privateDir.resolve("automodpack-client-secrets.json");
    public static final Path modpacksDir = automodpackDir.resolve("modpacks");
    public static final Path hostFullServerPackDir = automodpackDir.resolve("serverpack");

    public static final String clientConfigFileOverrideResource = "overrides-automodpack-client.json";
    public static String clientConfigOverride; // read from inside a jar file on preload, used instead of clientConfigFile if exists

    public static Path selectedModpackDir;
}
