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

    /**
     * Service files (as in {@link FileInspection#getSpecificServices}) that the <em>running loader
     * version</em> actually handles - a superset of {@link #inPlaceHandleableServices()}. The
     * force-copy decision counts only services in this set: a mod shipping a service this loader
     * version does not handle (a legacy or removed SPI, or a cross-loader SPI file that is inert
     * here) must <em>not</em> be copied to the standard {@code mods/} directory, because the loader
     * would not process that service there either - the copy can't help. A service in this set but
     * not in {@link #inPlaceHandleableServices()} (e.g. an early window provider) is the one case
     * where copying does help, so it force-copies.
     *
     * <p>The default is empty, meaning "no per-version information": the decision then falls back to
     * {@link FileInspection}'s built-in cross-version service set.
     */
    default Set<String> knownServices() {
        return Set.of();
    }
}
