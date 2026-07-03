package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.*;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

import java.nio.file.Path;

@SuppressWarnings("unused")
public class EarlyModLocator implements IModFileCandidateLocator {

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {

        // The update/reconcile step (Preload) now runs from EarlyServiceBootstrapper - the
        // loader's GraphicsBootstrapper phase, which always fires before mod discovery - so
        // ModpackLoader.modsToLoad is already populated by the time we get here.
        for (Path path : ModpackLoader.modsToLoad) {
            // Early-service jars (e.g. Sodium) were placed on a child SERVICE layer and
            // their GraphicsBootstrappers already fired. Their real mod lives in an inner
            // jar that their own candidate locator loads; the outer jar must NOT be added
            // as a mod here (it would duplicate the real mod's id), mirroring how the
            // loader skips SERVICE-layer jars during normal discovery.
            if (EarlyServiceLayer.isEarlyServiceJar(path)) {
                boolean coremod = EarlyServiceLayer.isCoremodJar(path);
                // Deliberately coremod-only, NOT every standalone jar: this mirrors native NeoForge,
                // which claims any mods/ jar shipping a TransformerDiscovererConstants service for
                // the SERVICE layer and excludes it from mod discovery - even if it has a root
                // neoforge.mods.toml. Standalone non-coremod service jars (asynclogger, Monocle)
                // ship their real mod behind their own dependency/candidate locator (replayed
                // below); addPath-ing their outer jar too would load the mod twice. ICoreMod is not
                // in that service set, so a standalone coremod jar DOES load natively as a mod.
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