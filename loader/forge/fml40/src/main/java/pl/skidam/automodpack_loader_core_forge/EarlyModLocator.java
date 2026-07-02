package pl.skidam.automodpack_loader_core_forge;

import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import pl.skidam.automodpack_loader_core.Preload;
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
        new Preload();

        // AutoModpack's own IModLocator service file (below) is what makes this loader promote
        // AutoModpack's jar to its SERVICE layer before mod discovery starts - so this is the
        // earliest point AutoModpack itself is ever invoked, and thus the right place to build the
        // shared child layer for the modpack folder's own early-service jars (there is no separate
        // GraphicsBootstrapper-style hook the way NeoForge has).
        EarlyServiceLayer.bootstrap(FMLPaths.GAMEDIR.get());

        // A split-jar early-service mod (no root mods.toml - its real mod is found by its own
        // IModLocator, replayed below) is not itself a loadable ModFile and must be excluded here,
        // or the base scanMods() below would fail trying to read it as one. A standalone
        // early-service jar (its own root mods.toml) is left in - it loads normally, like any other
        // mod, in addition to running its extra services in place.
        return ModpackLoader.modsToLoad.stream()
                .filter(path -> !EarlyServiceLayer.isEarlyServiceJar(path) || EarlyServiceLayer.isStandaloneModFile(path));
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
