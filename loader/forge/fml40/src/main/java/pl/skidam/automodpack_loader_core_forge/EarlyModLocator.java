package pl.skidam.automodpack_loader_core_forge;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import pl.skidam.automodpack_loader_core.Preload;
import pl.skidam.automodpack_loader_core_forge.mods.ModpackLoader;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class EarlyModLocator extends AbstractJarFileModLocator {

    @Override
    public void initArguments(Map<String, ?> arguments) { }

    @Override
    public String name() {
        return "automodpack_bootstrap";
    }

    @Override
    public Stream<Path> scanCandidates() {

        new Preload();

        return ModpackLoader.modsToLoad.stream();
    }
}