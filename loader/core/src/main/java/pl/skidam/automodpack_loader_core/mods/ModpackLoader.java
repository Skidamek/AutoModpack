package pl.skidam.automodpack_loader_core.mods;

import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public class ModpackLoader implements ModpackLoaderService {
	@Override
	public void loadModpack(ModpackLoadRequest request) {
		throw new AssertionError("Loader class not found");
	}

	@Override
	public List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileMetadataCache cache) {
		throw new AssertionError("Loader class not found");
	}
}
