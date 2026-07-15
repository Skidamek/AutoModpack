package pl.skidam.automodpack_loader_core_neoforge;

import java.util.List;
import java.util.Set;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

/**
 * A real, {@code META-INF/services}-shipped {@link ITransformationService} that forwards every
 * lifecycle call to each in-place early-service jar's own {@code ITransformationService} (see
 * {@link EarlyServiceLayer}). Discoverable the same way {@link AutoModpackCoreMod} already is:
 * AutoModpack's own jar ships {@code GraphicsBootstrapper}/{@code IModFileCandidateLocator}/
 * {@code IDependencyLocator} service files, so ModLauncher's {@code TransformationServicesHandler}
 * puts it on the SERVICE layer and {@code ServiceLoader} finds this class too.
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

	static final String NAME = "automodpack_early_services";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public void onLoad(IEnvironment env, Set<String> otherServices) {
		EarlyServiceLayer.forwardOnLoad(env, otherServices);
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
	public List<? extends ITransformer<?>> transformers() {
		return EarlyServiceLayer.collectTransformationServiceTransformers();
	}
}
