package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.fml.jarcontents.JarContents;
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
            // Early-service jars (e.g. Sodium) already had their GraphicsBootstrapper/etc run in
            // place, from FMLLoader's own classloader chain (EarlyServiceBootstrapper). Unlike fml4,
            // there's no separate module-layer bridge needed here: FMLLoader's game content loader
            // automatically falls back to the same chain we appended these jars to, so a standalone
            // early-service jar (its own root neoforge.mods.toml, e.g. modern Sodium) just needs to be
            // added as a regular mod file like any other - its classes are already visible everywhere
            // that matters. A split-jar shim (its real mod lives in a nested jar found by its own
            // IModFileCandidateLocator/IDependencyLocator, e.g. older split-jar mods) is NOT itself a
            // mod file and must not be added directly; only its locators are replayed.
            if (EarlyServiceLayer.isEarlyServiceJar(path)) {
                if (EarlyServiceLayer.isStandaloneModFile(path)) {
                    pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
                    try {
                        JarContents jarContents = JarContents.ofPath(path);
                        pipeline.readModFile(jarContents, ModFileDiscoveryAttributes.DEFAULT);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                // Forward any IModFileReader first, so custom-format candidates offered by the
                // locators below can be interpreted by it.
                EarlyServiceLayer.runModFileReaders(path, pipeline);
                EarlyServiceLayer.runCandidateLocators(path, context, pipeline);
                continue;
            }

            pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
            try {
                JarContents jarContents = JarContents.ofPath(path);
                pipeline.readModFile(jarContents, ModFileDiscoveryAttributes.DEFAULT);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public int getPriority() {
        return IModFileCandidateLocator.HIGHEST_SYSTEM_PRIORITY;
    }
}
