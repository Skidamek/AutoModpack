package pl.skidam.automodpack_loader_core_forge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import pl.skidam.automodpack_loader_core.Preload;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A real, {@code META-INF/services}-shipped {@link ITransformationService} that forwards every
 * lifecycle call to each in-place early-service jar's own {@code ITransformationService} (see
 * {@link EarlyServiceLayer}). Discovered the normal way through ModLauncher's own service lookup
 * (AutoModpack's jar ships {@code IModLocator}/{@code IDependencyLocator} service files, so it's
 * already on the SERVICE layer ServiceLoader scans).
 *
 * <p>Lifecycle calls below fire at ModLauncher's own native time, batched with every other real
 * transformation service, so no manual hook-point-picking is needed.
 *
 * <p>{@code onLoad} is also where AutoModpack runs its update/reconcile step ({@link Preload}) and
 * builds the shared child layer for the modpack folder's early-service jars ({@link
 * EarlyServiceLayer#bootstrap}) - the earliest hook Forge gives any mod, before {@link
 * EarlyModLocator#scanCandidates}. Running the update this early means a mod-list change is
 * already reflected in the folder discovery that happens later in the same boot.
 */
public class AutoModpackTransformationService implements ITransformationService {

    static final String NAME = "automodpack_early_services";

    // sun.java.command (not ProcessHandle, which is unreliable on some JVMs) carries the launch
    // args; FMLLoader.versionInfo()/getDist() are both still null/unreliable at onLoad() time.
    public static volatile String EARLY_MC_VERSION;
    public static volatile String EARLY_FORGE_VERSION;
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
            EARLY_IS_CLIENT = !launchTarget.toLowerCase(Locale.ROOT).contains("server");
        }

        new Preload();
        EarlyServiceLayer.bootstrap();
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
