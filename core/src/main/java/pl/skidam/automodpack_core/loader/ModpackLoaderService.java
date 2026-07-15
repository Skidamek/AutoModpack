package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public interface ModpackLoaderService {
	void loadModpack(List<Path> modpackMods);

	List<FileInspection.Mod> getModpackNestedConflicts(Path modpackDir, FileMetadataCache cache); // Returns list of mods from the modpack Dir that are
																									// conflicting with the mods from standard mods dir

	/**
	 * Service files (paths under {@code META-INF/services/}) this loader generation cannot host in
	 * place - a modpack mod shipping any of these must be copied into the standard {@code mods/}
	 * directory instead of being left in the modpack folder. The default is none.
	 */
	default Set<String> forceCopyServices() {
		return Set.of();
	}
}
