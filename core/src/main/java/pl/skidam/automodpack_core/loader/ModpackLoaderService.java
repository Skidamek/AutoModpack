package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface ModpackLoaderService {
    void loadModpack(List<Path> modpackMods);
    List<LoaderManagerService.Mod> getModpackNestedConflicts(Path modpackDir, Set<String> ignoredMods);
}
