package pl.skidam.automodpack_loader_core_forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import pl.skidam.automodpack_core.Preload;
import pl.skidam.automodpack_core.loader.EarlyLaunchEnvironment;
import pl.skidam.automodpack_core.loader.GenerationProbes;
import pl.skidam.automodpack_core.loader.TargetId;
import pl.skidam.automodpack_loader_core_forge.loader.LoaderManager;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;
import pl.skidam.automodpack_loader_core_modlauncher.ModLauncherEarlyServiceBridge;
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

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public void onLoad(IEnvironment env, Set<String> otherServices) {
		if (!GenerationProbes.FORGE_PRESENT) return;

		// FMLLoader.versionInfo()/getDist() are still null at onLoad() (processArguments/initialize
		// have not run yet). ModLauncher already has the real launch args in ArgumentHandler; the
		// JVM's sun.java.command does not when Prism launches through ForgeWrapper by reflection.
		// ModLauncher's stored args are authoritative; the JVM's view fills whatever they miss.
		EarlyLaunchEnvironment.captureFromArguments(ModuleClassLoaderAccess.launchArguments(), "--fml.forgeVersion");
		EarlyLaunchEnvironment.captureFromArguments(System.getProperty("sun.java.command", "").split("\\s+"), "--fml.forgeVersion");

		TargetId.id("forge", EarlyLaunchEnvironment.MC_VERSION);

		new Preload(new LoaderManager(), new ModpackLoader());
		EarlyServiceLayer.bootstrap();
		ModLauncherEarlyServiceBridge.forEachTransformationService("onLoad", service -> service.onLoad(env, otherServices));
	}

	@Override
	public void initialize(IEnvironment environment) {
		if (!GenerationProbes.FORGE_PRESENT) return;
		ModLauncherEarlyServiceBridge.forEachTransformationService("initialize", service -> service.initialize(environment));
	}

	@Override
	public List<Resource> beginScanning(IEnvironment environment) {
		if (!GenerationProbes.FORGE_PRESENT) return List.of();
		return ModLauncherEarlyServiceBridge.collectFromTransformationServices("beginScanning", service -> service.beginScanning(environment));
	}

	@Override
	public List<ITransformationService.Resource> completeScan(IModuleLayerManager layerManager) {
		if (!GenerationProbes.FORGE_PRESENT) return List.of();
		return ModLauncherEarlyServiceBridge.collectFromTransformationServices("completeScan", service -> service.completeScan(layerManager));
	}

	@Override
	public List<ITransformer> transformers() {
		if (!GenerationProbes.FORGE_PRESENT) return List.of();
		return ModLauncherEarlyServiceBridge.collectFromTransformationServices("transformers", service -> new ArrayList<>(service.transformers()));
	}
}
