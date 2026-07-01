package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.util.List;
import java.util.Set;

/**
 * A ModLauncher {@link ITransformationService} that AutoModpack <em>injects</em> into
 * {@code TransformationServicesHandler.serviceLookup} (see {@link EarlyServiceLayer#
 * injectForwardingTransformationService()}). It cannot be discovered the way {@link
 * AutoModpackCoreMod} is: {@code ICoreMod} is a NeoForge SPI scanned during mod loading from the
 * SERVICE layer, but {@code ITransformationService} is a ModLauncher SPI discovered at bootstrap from
 * the BOOT layer - before AutoModpack's SERVICE layer exists - so a shipped service file would never
 * be seen (the same reason {@link EarlyServiceBridgePlugin} is injected, not discovered).
 *
 * <p>Its one job is {@link #completeScan}: ModLauncher's {@code triggerScanCompletion} calls every
 * registered service's {@code completeScan} <em>after</em> mod discovery and adds the returned jars to
 * the GAME/PLUGIN layers as it builds them. By forwarding to each in-place early-service jar's own
 * transformation service here, resources those services contribute - e.g. Sinytra Connector's
 * {@code FabricASMFixer} generated-classes jar and its {@code authlib}/{@code brigadier} module moves,
 * which its Fabric mods' mixins need - reach the layers natively, at the correct time. Running
 * {@code completeScan} ourselves later (at the bridge phase) could not do this: the GAME layer is
 * already built by then.
 *
 * <p>{@code onLoad}/{@code initialize} are no-ops: ModLauncher calls those before we can inject, so
 * {@link EarlyServiceLayer#runTransformationServiceOnLoad()} drives them in place instead.
 */
public class AutoModpackTransformationService implements ITransformationService {

    static final String NAME = "automodpack_early_services";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(IEnvironment environment) {
        // Driven in place by EarlyServiceBootstrapper - ModLauncher already passed this phase.
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        // Driven in place by EarlyServiceBootstrapper - ModLauncher already passed this phase.
    }

    @Override
    public List<ITransformationService.Resource> completeScan(IModuleLayerManager layerManager) {
        return EarlyServiceLayer.forwardCompleteScan(layerManager);
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        // Real transformers are forwarded via AutoModpackCoreMod; nothing to add here.
        return List.of();
    }
}
