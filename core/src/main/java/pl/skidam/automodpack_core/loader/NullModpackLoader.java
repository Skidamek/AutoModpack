package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class NullModpackLoader implements ModpackLoaderService {

    @Override
    public void loadModpack(List<Path> modpackMods) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public List<LoaderManagerService.Mod> getModpackNestedConflicts(Path modpackDir, Set<String> ignoredMods) {
        throw new AssertionError("Loader class not found");
    }
}
