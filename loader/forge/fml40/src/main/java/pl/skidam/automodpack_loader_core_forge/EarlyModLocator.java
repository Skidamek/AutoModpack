package pl.skidam.automodpack_loader_core_forge;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator {

    @Override
    public void initArguments(Map<String, ?> arguments) {}

    @Override
    public String name() {
        return "automodpack";
    }

    @Override
    public Stream<Path> scanCandidates() {
        // The update/reconcile step (Preload) and the early-service child-layer bootstrap now run
        // from AutoModpackTransformationService#onLoad - ModLauncher's own ITransformationService
        // lifecycle, which fires before this IModLocator pass - so ModpackLoader.modsToLoad and
        // EarlyServiceLayer's registered jars are already populated by the time we get here.

        // Early-service jars must NOT load as mods here, even with a root mods.toml: native Forge
        // likewise claims them for the SERVICE layer and excludes them from mod discovery
        // (ModsFolderLocator filters ModDirTransformerDiscoverer.allExcluded()). Their real mod
        // arrives through their own IModLocator, replayed below - loading the outer jar too would
        // double-load it (e.g. CrashAssistant's outer and inner jar share one modId).
        return ModpackLoader.modsToLoad.stream()
                .filter(path -> !EarlyServiceLayer.isEarlyServiceJar(path));
    }

    // Forge 1.18.2's IModLocator#scanMods() returns List<IModFile> directly (no ModFileOrException
    // wrapper - that type was only added in a later forgespi version), unlike fml47's override.
    @Override
    public List<IModFile> scanMods() {
        List<IModFile> results = new ArrayList<>(super.scanMods());
        for (Path jar : EarlyServiceLayer.registeredJars()) {
            List<Object> extra = new ArrayList<>();
            EarlyServiceLayer.runCandidateLocators(jar, extra);
            for (Object o : extra) {
                results.add((IModFile) o);
            }
        }
        return results;
    }
}
