package pl.skidam.automodpack_loader_core.mods;

import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.nio.file.Path;
import java.util.List;

public class ModpackLoader implements ModpackLoaderService {
    @Override
    public void loadModpack(List<Path> modpackMods) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public List<FileInspection.Mod> getModpackNestedConflicts(Path modpackDir) {
        throw new AssertionError("Loader class not found");
    }
}
