package pl.skidam.automodpack_core.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface ModpackLoaderService {
    void loadModpack(List<Path> modpackMods);
    boolean prepareModpack(Path modpackDir, Set<String> ignoredMods) throws IOException;
}
