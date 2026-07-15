package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.List;

import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public class NullModpackLoader implements ModpackLoaderService {

	@Override
	public void loadModpack(List<Path> modpackMods) {
		throw new AssertionError("Loader class not found");
	}

	@Override
	public List<FileInspection.Mod> getModpackNestedConflicts(Path modpackDir, FileMetadataCache cache) {
		throw new AssertionError("Loader class not found");
	}
}
