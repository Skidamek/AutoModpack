package pl.skidam.automodpack_core.loader;

import pl.skidam.automodpack_core.utils.FileInspection;

import java.nio.file.Path;
import java.util.List;

public interface ModpackLoaderService {
    void loadModpack(List<Path> modpackMods);
    List<FileInspection.Mod> getModpackNestedConflicts(Path modpackDir);
}
