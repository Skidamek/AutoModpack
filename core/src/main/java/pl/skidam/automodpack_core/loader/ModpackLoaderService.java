package pl.skidam.automodpack_core.loader;

import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface ModpackLoaderService {
    void loadModpack(List<Path> modpackMods);
    List<FileInspection.Mod> getModpackNestedConflicts(Path modpackDir, FileMetadataCache cache); // Returns list of mods from the modpack Dir that are conflicting with the mods from standard mods dir

    /**
     * Service files (paths under {@code META-INF/services/}, matching
     * {@link FileInspection#getSpecificServices}) that this loader can run directly from the
     * modpack folder, so a mod shipping <em>only</em> these never needs copying into the
     * standard {@code mods/} directory. The default is none - loaders that can't host any
     * service in place keep the copy-to-standard workaround for every service mod.
     */
    default Set<String> inPlaceHandleableServices() {
        return Set.of();
    }
}
