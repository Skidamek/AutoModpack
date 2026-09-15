package pl.skidam.automodpack_loader_core_neoforge_4;

import java.util.List;
import java.util.Set;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import pl.skidam.automodpack_core.loader.GenerationProbes;

/**
 * A real, {@code META-INF/services}-shipped {@link ITransformationService} that forwards every
 * lifecycle call to each in-place early-service jar's own {@code ITransformationService} (see
 * {@link EarlyServiceLayer}). Discoverable the same way {@link AutoModpackCoreMod} already is:
 * AutoModpack's own jar ships {@code GraphicsBootstrapper}/{@code IModFileCandidateLocator}/
 * {@code IDependencyLocator} service files, so ModLauncher's {@code TransformationServicesHandler}
 * puts it on the SERVICE layer and {@code ServiceLoader} finds this class too.
 *
 * <p>
 * The universal outer jar also carries legacy Forge's transformation service (which ServiceLoader
 * finds on this loader too), so this one keeps its own name and stays inert
 * ({@link GenerationProbes#NEOFORGE_FML4}) wherever it gets loaded outside NeoForge's
 * ModLauncher-era generation - otherwise ModLauncher's name-keyed service map would see a duplicate.
 * Both services type-check on every ModLauncher-era loader: ITransformationService's abstract surface
 * (name/onLoad/initialize/transformers) is erasure-identical across modlauncher 9.x, 10.x and 11.x,
 * and everything NeoForge-specific sits behind the guard.
 *
 * <p>
 * This makes every lifecycle call below fire at ModLauncher's own native time, batched with every
 * other real transformation service: all {@code onLoad}, then all {@code initialize}, then all
 * {@code beginScanning}, then (after mod discovery) {@code completeScan}, then {@code transformers}.
 * That ordering is what modpack-folder services need - in particular Sinytra Connector's
 * {@code initialize()}, which must run after NeoForge's window-provider assignment.
 *
 * <p>
 * The in-place services themselves are instantiated at the earlywindow bootstrap phase (see
 * {@link EarlyServiceLayer#instantiateTransformationServices()}), before this class's {@code onLoad}
 * can possibly run, so every in-place service exists by the time ModLauncher calls any method here.
 */
public class AutoModpackTransformationService implements ITransformationService {

	static final String NAME = "automodpack_early_services_neoforge";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public void onLoad(IEnvironment env, Set<String> otherServices) {
		if (!GenerationProbes.NEOFORGE_FML4) return;
		EarlyServiceLayer.forwardOnLoad(env, otherServices);
	}

	@Override
	public void initialize(IEnvironment environment) {
		if (!GenerationProbes.NEOFORGE_FML4) return;
		EarlyServiceLayer.forwardInitialize(environment);
	}

	@Override
	public List<Resource> beginScanning(IEnvironment environment) {
		if (!GenerationProbes.NEOFORGE_FML4) return List.of();
		return EarlyServiceLayer.forwardBeginScanning(environment);
	}

	@Override
	public List<ITransformationService.Resource> completeScan(IModuleLayerManager layerManager) {
		if (!GenerationProbes.NEOFORGE_FML4) return List.of();
		return EarlyServiceLayer.forwardCompleteScan(layerManager);
	}

	@Override
	public List<? extends ITransformer<?>> transformers() {
		if (!GenerationProbes.NEOFORGE_FML4) return List.of();
		return EarlyServiceLayer.collectTransformationServiceTransformers();
	}
}
