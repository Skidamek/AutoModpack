package pl.skidam.automodpack_loader_core.mods;

import java.nio.file.Path;
import java.util.List;

public class ModpackLoader implements ModpackLoaderService {
    @Override
    public void loadModpack(List<Path> modpackMods) {
        throw new AssertionError("Loader class not found");
    }
}
