package pl.skidam.automodpack_loader_core.mods;

import java.nio.file.Path;
import java.util.List;

import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileCache;

public class ModpackLoader implements ModpackLoaderService {
	@Override
	public void loadModpack(ModpackLoadRequest request) {
		throw new AssertionError("Loader class not found");
	}

	@Override
	public List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileCache cache) {
		throw new AssertionError("Loader class not found");
	}
}
