package pl.skidam.automodpack_loader_core_neoforge;

import net.neoforged.fml.loading.moddiscovery.AbstractJarFileDependencyLocator;
import net.neoforged.neoforgespi.locating.IModFile;

import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class LazyModLocator extends AbstractJarFileDependencyLocator {
    @Override
    public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        return List.of();
    }

    @Override
    public String name() {
        return "automodpack";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) { }
}
