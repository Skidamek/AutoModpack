package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.jarhandling.JarContents;
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
                    // a regular mod with an inline coremod. Its coremod transformers are forwarded
                    // by AutoModpackCoreMod (it was registered on the child SERVICE layer at
                    // bootstrap); the mod loads here as its real self, mods.toml intact. A stripped
                    // library copy would gut that mods.toml, so it must not be used here.
                    pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
                    pipeline.readModFile(JarContents.of(path), ModFileDiscoveryAttributes.DEFAULT);
                }
                // A non-coremod jar's inner mod references classes from the outer jar. Natively
                // those resolve because the outer jar is on the SERVICE layer, a GAME ancestor; our
                // child layer is only a sibling. Rather than add a second, uninitialised copy of the
                // outer classes to the GAME layer (the split that crashed asynclogger), AutoModpack's
                // ILaunchPluginService (EarlyServiceBridgePlugin) points the GAME classloader at the
                // child layer - before Mixin loads any outer class - so the inner mod reads the very
                // class the GraphicsBootstrapper already fired on. No copy needed.
                // A non-standalone coremod jar (e.g. Sinytra Connector) runs its own dependency
                // locator + a ForgeModPackageFilter that strips its own packages from every mod
                // file already in the discovery set. Adding its copy now would feed it to that
                // filter and gut it, so that copy is added later, from LazyModLocator, once the
                // coremod's locator has finished.
                EarlyServiceLayer.runCandidateLocators(path, context, pipeline);
                continue;
            }

            pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
            pipeline.readModFile(JarContents.of(path), ModFileDiscoveryAttributes.DEFAULT);
        }
    }

    @Override
    public int getPriority() {
        return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
    }
}