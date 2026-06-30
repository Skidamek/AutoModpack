package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.*;
import pl.skidam.automodpack_loader_core_neoforge.mods.ModpackLoader;

import java.nio.file.Path;
import java.util.List;

@SuppressWarnings("unused")
public class LazyModLocator implements IDependencyLocator {

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        try {
            SecureJar secureJar = SecureJar.from(Path.of(LazyModLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
            IModFile modFile = IModFile.create(secureJar, JarModsDotTomlModFileReader::manifestParser);
            new JarInJarDependencyLocator().scanMods(List.of(modFile), pipeline);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Replay the dependency locators of early-service jars (e.g. Ixeris) so their real
        // (inner) mod loads in place, from the modpack folder, without being copied to the
        // standard mods directory.
        for (Path path : ModpackLoader.modsToLoad) {
            if (!EarlyServiceLayer.isEarlyServiceJar(path)) {
                continue;
            }
            EarlyServiceLayer.runDependencyLocators(path, loadedMods, pipeline);

            // For a non-standalone coremod jar (e.g. Sinytra Connector) the game-library copy is
            // added here, AFTER its own dependency locator has run, rather than during the
            // candidate phase. The coremod's locator runs a ForgeModPackageFilter that strips its
            // own packages (e.g. org.sinytra.connector, which holds ConnectorEarlyLoader) from
            // every mod file already in the discovery set; adding the copy beforehand would gut it.
            // Added last (this locator has the lowest priority), the copy reaches the GAME layer
            // untouched, so the inner mod can resolve the outer jar's classes in place. A coremod
            // jar that is itself a mod (root neoforge.mods.toml) was already added as its real self
            // by EarlyModLocator and needs no copy.
            if (EarlyServiceLayer.isCoremodJar(path) && !EarlyServiceLayer.isStandaloneModFile(path)) {
                EarlyServiceLayer.addLibraryCopy(path, pipeline);
            }
        }
    }

    @Override
    public int getPriority() {
        return IModFileCandidateLocator.LOWEST_SYSTEM_PRIORITY;
    }
}
