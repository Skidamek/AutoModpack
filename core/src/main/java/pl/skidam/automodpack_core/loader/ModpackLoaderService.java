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
     * modpack folder, so a mod shipping only these never needs copying into the standard
     * {@code mods/} directory. The default is none.
     */
    default Set<String> inPlaceHandleableServices() {
        return Set.of();
    }

    /**
     * Service files that the running loader version actually handles - a superset of
     * {@link #inPlaceHandleableServices()}. The force-copy decision counts only services in this
     * set: a service the running version doesn't handle (a legacy/removed SPI, or an inert
     * cross-loader file) must not be copied, since the loader wouldn't process it there either.
     * The default is empty, falling back to {@link FileInspection}'s cross-version service set.
     */
    default Set<String> knownServices() {
        return Set.of();
    }
}
