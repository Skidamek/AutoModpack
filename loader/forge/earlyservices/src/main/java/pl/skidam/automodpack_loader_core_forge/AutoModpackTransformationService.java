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
 * <p>The in-place services themselves are instantiated by {@link EarlyServiceLayer#bootstrap}, called
 * from {@link EarlyModLocator#scanMods} - the same {@code IModLocator} pass that puts AutoModpack's
 * own jar on the SERVICE layer in the first place, so it always runs before ModLauncher can discover
 * (let alone call) this class.
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
    public List<ITransformer> transformers() {
        return EarlyServiceLayer.collectTransformationServiceTransformers();
    }
}
