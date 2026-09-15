package pl.skidam.automodpack_loader_core_forge;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import pl.skidam.automodpack_core.Preload;
import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_core.loader.TargetId;
import pl.skidam.automodpack_loader_core_forge.loader.LoaderManager;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;
import pl.skidam.automodpack_loader_core_modlauncher.ModuleClassLoaderAccess;

/**
 * A real, {@code META-INF/services}-shipped {@link ITransformationService} that forwards every
 * lifecycle call to each in-place early-service jar's own {@code ITransformationService} (see
 * {@link EarlyServiceLayer}). Discovered the normal way through ModLauncher's own service lookup
 * (AutoModpack's jar ships {@code IModLocator}/{@code IDependencyLocator} service files, so it's
 * already on the SERVICE layer ServiceLoader scans).
 *
 * <p>
 * The universal outer jar also carries NeoForge fml4's transformation service, so this one stays
 * inert ({@link GenerationProbes#FORGE_PRESENT}) wherever it gets ServiceLoaded outside legacy
 * Forge. Both services type-check on every ModLauncher-era loader: ITransformationService's abstract
 * surface (name/onLoad/initialize/transformers) is erasure-identical across modlauncher 9.x, 10.x
 * and 11.x, and everything Forge-specific in the bodies sits behind the guard. Every lifecycle call
 * the loader still makes on an inert service short-circuits before touching any Forge-only type.
 *
 * <p>
 * Lifecycle calls below fire at ModLauncher's own native time, batched with every other real
 * transformation service, so no manual hook-point-picking is needed.
 *
 * <p>
 * {@code onLoad} is also where AutoModpack detects its target id and runs its update/reconcile step
 * ({@link Preload}) and builds the shared child layer for the active projection's early-service jars
 * ({@link EarlyServiceLayer#bootstrap}) - the earliest hook Forge gives any mod, before {@link
 * EarlyModLocator#scanCandidates}. Running the update this early means a mod-list change is already
 * reflected in the folder discovery that happens later in the same boot.
 */
public class AutoModpackTransformationService implements ITransformationService {

	static final String NAME = "automodpack_early_services";

	// FMLLoader.versionInfo()/getDist() are still null at onLoad() (processArguments/initialize
	// have not run yet). ModLauncher already has the real launch args in ArgumentHandler; the
	// JVM's sun.java.command does not when Prism launches through ForgeWrapper by reflection.
	public static volatile String EARLY_MC_VERSION;
	public static volatile String EARLY_FORGE_VERSION;
	public static volatile Boolean EARLY_IS_CLIENT;

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public void onLoad(IEnvironment env, Set<String> otherServices) {
		if (!GenerationProbes.FORGE_PRESENT) return;

		String[] launchArgs = ModuleClassLoaderAccess.launchArguments();
		String[] processArgs = System.getProperty("sun.java.command", "").split("\\s+");
		EARLY_MC_VERSION = firstNonNull(argValue(launchArgs, "--fml.mcVersion"), argValue(processArgs, "--fml.mcVersion"));
		EARLY_FORGE_VERSION = firstNonNull(argValue(launchArgs, "--fml.forgeVersion"), argValue(processArgs, "--fml.forgeVersion"));
		String launchTarget = firstNonNull(argValue(launchArgs, "--launchTarget"), argValue(processArgs, "--launchTarget"));
		if (launchTarget != null) EARLY_IS_CLIENT = !launchTarget.toLowerCase(Locale.ROOT).contains("server");

		// TargetId throws when the id cannot be resolved: a launch without a target id must crash,
		// not silently run on an unknown combination.
		LOGGER.info("AutoModpack target: {}", TargetId.id("forge", EARLY_MC_VERSION));

		new Preload(new LoaderManager(), new ModpackLoader());
		EarlyServiceLayer.bootstrap();
		EarlyServiceLayer.forwardOnLoad(env, otherServices);
	}

	private static String firstNonNull(String preferred, String fallback) {
		return preferred != null ? preferred : fallback;
	}

	private static String argValue(String[] arguments, String name) {
		if (arguments == null) return null;
		String prefix = name + "=";
		for (int i = 0; i < arguments.length; i++) {
			if (name.equals(arguments[i]) && i + 1 < arguments.length) return arguments[i + 1];
			if (arguments[i].startsWith(prefix)) return arguments[i].substring(prefix.length());
		}
		return null;
	}

	@Override
	public void initialize(IEnvironment environment) {
		if (!GenerationProbes.FORGE_PRESENT) return;
		EarlyServiceLayer.forwardInitialize(environment);
	}

	@Override
	public List<Resource> beginScanning(IEnvironment environment) {
		if (!GenerationProbes.FORGE_PRESENT) return List.of();
		return EarlyServiceLayer.forwardBeginScanning(environment);
	}

	@Override
	public List<ITransformationService.Resource> completeScan(IModuleLayerManager layerManager) {
		if (!GenerationProbes.FORGE_PRESENT) return List.of();
		return EarlyServiceLayer.forwardCompleteScan(layerManager);
	}

	@Override
	public List<ITransformer> transformers() {
		if (!GenerationProbes.FORGE_PRESENT) return List.of();
		return EarlyServiceLayer.collectTransformationServiceTransformers();
	}
}
