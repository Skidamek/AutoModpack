package pl.skidam.automodpack_core;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.GameCallService;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.loader.NullGameCall;
import pl.skidam.automodpack_core.loader.NullLoaderManager;
import pl.skidam.automodpack_core.loader.NullModpackLoader;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

// More or less constants
// TODO cleanup
public class Constants {
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
	public static Jsons.ServerConfigFieldsV3 serverConfig;
	public static Jsons.ClientConfigFieldsV3 clientConfig;
	public static final Path automodpackDir = Path.of("automodpack");
	public static final Path clientDir = automodpackDir.resolve("client");
	public static final Path clientRecordsDir = clientDir.resolve("records");
	public static final Path clientOverlaysDir = clientDir.resolve("overlays");
	public static final Path clientBaselinesDir = clientDir.resolve("baselines");
	public static final Path clientGeneratedCopiesDir = clientDir.resolve("generated-copies");
	public static final Path clientActiveDir = clientDir.resolve("active");
	public static final Path clientIncomingDir = clientDir.resolve("incoming");
	public static final Path clientBackupDir = clientDir.resolve("backup");
	public static final Path clientRecoveryDir = clientDir.resolve("recovery");
	public static final Path clientQuarantineDir = clientDir.resolve("quarantine");
	public static final Path clientActiveStateFile = clientDir.resolve("active-state.json");
	public static final Path clientSelectionFile = clientDir.resolve("selections.json");
	public static final Path clientRestartLoopStateFile = clientDir.resolve("restart-state.json");
	public static final Path clientTransactionFile = clientDir.resolve("update-transaction.json");
	public static final Path clientContentTempFile = clientDir.resolve("incoming-content.json.temp");
	public static final Path clientHelperDir = clientDir.resolve("helper");
	public static final Path serverDir = automodpackDir.resolve("server");
	public static final Path serverCurrentFile = serverDir.resolve("current.json");
	public static final Path serverCurrentProjectionFile = serverDir.resolve("current-projection.json");
	public static final Path serverGenerationCheckpointFile = serverDir.resolve("checkpoint.json");
	public static final Path serverCataloguesDir = serverDir.resolve("catalogues");
	public static final Path serverCommitsDir = serverDir.resolve("commits");
	public static final Path serverDeltasDir = serverDir.resolve("deltas");
	public static final Path serverStagingDir = serverDir.resolve("staging");
	public static final Path serverPatchNotesFile = serverDir.resolve("patch-notes.md");
	public static final Path serverSecretsFile = serverDir.resolve("secrets.json");
	public static final Path serverCertFile = serverDir.resolve("certificate.crt");
	public static final Path serverPrivateKeyFile = serverDir.resolve("private-key.pem");
	public static final Path hostModpackDir = automodpackDir.resolve("host-modpack");
	// TODO More server modpacks
	// Main - required
	// Addons - optional addon packs
	// Switches - optional or required packs, chosen by the player, only one can be installed at a time
	public static final Path hostContentModpackDir = hostModpackDir.resolve("main");
	public static final Path modpackContentFileName = Path.of("automodpack-content.json");
	public static final Path serverConfigFile = automodpackDir.resolve("server-config.json");
	public static final Path bootstrapFile = Path.of("automodpack-bootstrap.json");
	public static final Path clientConfigFile = automodpackDir.resolve("client-config.json");

}
