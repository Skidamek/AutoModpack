package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public interface ModpackLoaderService {
	void loadModpack(ModpackLoadRequest request);

	List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileMetadataCache cache); // Returns mod conflicts found in the active projection.

	/**
	 * Service files (paths under {@code META-INF/services/}) this loader generation cannot host in
	 * place - a modpack mod shipping any of these must be copied into the standard {@code mods/}
	 * directory instead of being loaded from the active projection. The default is none.
	 */
	default Set<String> forceCopyServices() {
		return Set.of();
	}
}
