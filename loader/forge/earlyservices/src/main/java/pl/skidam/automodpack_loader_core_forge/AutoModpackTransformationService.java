package pl.skidam.automodpack_loader_core_forge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.util.List;
import java.util.Set;

/**
 * A real, {@code META-INF/services}-shipped {@link ITransformationService} that forwards every
 * lifecycle call to each in-place early-service jar's own {@code ITransformationService} (see
 * {@link EarlyServiceLayer}). Discoverable the normal way: ModLauncher's
 * {@code TransformationServicesHandler.discoverServices} builds the SERVICE layer from every jar
 * in the standard {@code mods/} folder that ships any of {@code
 * ModDirTransformerDiscoverer.SERVICES} - AutoModpack's own jar already qualifies (it ships
 * {@code IModLocator}/{@code IDependencyLocator} service files), so it is on that layer, and
 * {@code ServiceLoader.load(serviceLayer, ITransformationService.class)} - called right after -
 * finds this class too.
 *
 * <p>This makes every lifecycle call below fire at ModLauncher's own native time, batched with every
 * other real transformation service: all {@code onLoad}, then all {@code initialize}, then all {@code
 * beginScanning}, then (after mod discovery) {@code completeScan}, then {@code transformers}. No
 * manual hook-point-picking is needed - that ordering is ModLauncher's own native invariant.
 *
 * <p>{@code onLoad} is also where AutoModpack runs its own update/reconcile step ({@code Preload})
 * and builds the shared child layer for the modpack folder's early-service jars ({@link
 * EarlyServiceLayer#bootstrap}) - confirmed live to be the earliest hook Forge gives any mod
 * (fires before {@link EarlyModLocator#scanCandidates}, and {@code FMLPaths.GAMEDIR} is already
 * populated by this point). Running the update here, before anything scans the modpack folder,
 * means an update that changes which mods are early-service mods is already reflected in the
 * folder mod discovery sees later in the same boot - no restart needed.
 */
public class AutoModpackTransformationService implements ITransformationService {

    static final String NAME = "automodpack_early_services";

    // FMLLoader.versionInfo() is still null at onLoad() time - it's only populated later, from
    // FML's own ITransformationService#initialize (and ModLauncher gives no ordering guarantee
    // between different services' initialize() calls even in that phase, so waiting for our own
    // initialize() wouldn't be safe either). Unlike NeoForge's GraphicsBootstrapper#bootstrap,
    // Forge's ITransformationService#onLoad isn't handed the raw launch arguments, and
    // ProcessHandle.current().info().arguments() is unreliable (confirmed live: returns an empty
    // array on at least one JVM/OS combination this runs on), so parse them out of
    // "sun.java.command" instead - a JVM system property always set to the full command line
    // (main class + program args) by the launcher itself, present on every JVM tested.
    public static volatile String EARLY_MC_VERSION;
    public static volatile String EARLY_FORGE_VERSION;
    // FMLLoader.getDist() is also unreliable at onLoad() time, for the same reason versionInfo() is
    // (see above) - confirmed live on NeoForge fml4 to be the actual cause of a regression: reading
    // it too early can silently return SERVER on a genuine CLIENT launch, so Preload.updateAll()
    // takes its dedicated-server-only branch and never populates the mod list early-service
    // discovery depends on. Forge's launchTarget naming convention (e.g. "forgeclient"/
    // "forgeserver") reliably encodes dist and is on the same command line as the version args above.
    public static volatile Boolean EARLY_IS_CLIENT;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        String[] processArgs = System.getProperty("sun.java.command", "").split("\\s+");
        EARLY_MC_VERSION = argValue(processArgs, "--fml.mcVersion");
        EARLY_FORGE_VERSION = argValue(processArgs, "--fml.forgeVersion");
        String launchTarget = argValue(processArgs, "--launchTarget");
        if (launchTarget != null) {
            EARLY_IS_CLIENT = !launchTarget.toLowerCase(java.util.Locale.ROOT).contains("server");
        }

        new pl.skidam.automodpack_loader_core.Preload();
        EarlyServiceLayer.bootstrap(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
        EarlyServiceLayer.forwardOnLoad(env, otherServices);
    }

    private static String argValue(String[] arguments, String name) {
        String prefix = name + "=";
        for (int i = 0; i < arguments.length; i++) {
            if (name.equals(arguments[i]) && i + 1 < arguments.length) {
                return arguments[i + 1];
            }
            if (arguments[i].startsWith(prefix)) {
                return arguments[i].substring(prefix.length());
            }
        }
        return null;
    }

    @Override
    public void initialize(IEnvironment environment) {
        EarlyServiceLayer.forwardInitialize(environment);
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        return EarlyServiceLayer.forwardBeginScanning(environment);
    }

    @Override
    public List<ITransformationService.Resource> completeScan(IModuleLayerManager layerManager) {
        return EarlyServiceLayer.forwardCompleteScan(layerManager);
    }

    @Override
    public List<ITransformer> transformers() {
        return EarlyServiceLayer.collectTransformationServiceTransformers();
    }
}
