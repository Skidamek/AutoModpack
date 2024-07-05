package pl.skidam.automodpack_loader_core.mods;

import java.nio.file.Path;
import java.util.List;

public interface ModpackLoaderService {
    void loadModpack(List<Path> modpackMods);
}
