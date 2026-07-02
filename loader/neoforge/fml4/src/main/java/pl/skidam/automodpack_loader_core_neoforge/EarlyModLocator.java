package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

import java.nio.file.Path;

@SuppressWarnings("unused")
public class EarlyModLocator implements IModFileCandidateLocator {

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {

        // Deliberately NOT moved into EarlyServiceBootstrapper's GraphicsBootstrapper phase (unlike
        // fml10/fml11/forge, where an earlier Preload avoids a restart after an update changes which
        // mods are early-service mods): a live regression showed that calling Preload from anywhere
        // within GraphicsBootstrapper.bootstrap() breaks Sinytra Connector's own updateModuleReads
        // (Class.forName("...DummyTarget") throws ClassNotFoundException at FMLLoader.beforeStart) -
        // confirmed via A/B testing that this loader's ModLauncher/ITransformationService machinery,
        // which Connector's own service instantiation and lifecycle run through, is what's sensitive
        // to it, not a fixable timing gap. fml10/fml11 have no such competing ITransformationService
        // phase and were verified safe with the earlier placement.
        ProgressMeter progress = StartupNotificationManager.prependProgressBar("[Automodpack] Preload", 0);
        new Preload();
        progress.complete();

        for (Path path : ModpackLoader.modsToLoad) {
            // Early-service jars (e.g. Sodium) were placed on a child SERVICE layer and
            // their GraphicsBootstrappers already fired. Their real mod lives in an inner
            // jar that their own candidate locator loads; the outer jar must NOT be added
            // as a mod here (it would duplicate the real mod's id), mirroring how the
            // loader skips SERVICE-layer jars during normal discovery.
            if (EarlyServiceLayer.isEarlyServiceJar(path)) {
                boolean coremod = EarlyServiceLayer.isCoremodJar(path);
                if (coremod && EarlyServiceLayer.isStandaloneModFile(path)) {
                    // A jar that ships a coremod AND is itself a mod (root neoforge.mods.toml) -
                    // a regular mod with an inline coremod. It loads here as its real self, mods.toml
                    // intact, so its classes are on the GAME layer directly (no bridge); its coremod
                    // transformers are forwarded by AutoModpackCoreMod (it was registered on the
                    // child SERVICE layer at bootstrap).
                    pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
                }
                // Every other early-service jar - a split jar (Sodium) or a non-standalone coremod
                // (Sinytra Connector) - keeps its outer classes only on its child SERVICE layer. Its
                // inner mod references those classes; natively they resolve because the outer jar is
                // on the SERVICE layer, a GAME ancestor, but our child layer is only a sibling. Rather
                // than add a second, uninitialised copy of the outer classes to the GAME layer (the
                // split that crashed asynclogger), AutoModpack's ILaunchPluginService
                // (EarlyServiceBridgePlugin) points the GAME classloader at the child layer - for
                // both classes and resources, before Mixin loads any outer class - so the inner mod
                // reads the very class the GraphicsBootstrapper already fired on. No copy needed.
                //
                // Forward any IModFileReader first, so custom-format candidates offered by the
                // locators below can be interpreted by it.
                EarlyServiceLayer.runModFileReaders(path, pipeline);
                EarlyServiceLayer.runCandidateLocators(path, context, pipeline);
                continue;
            }

            pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        }
    }

    @Override
    public int getPriority() {
        return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
    }
}