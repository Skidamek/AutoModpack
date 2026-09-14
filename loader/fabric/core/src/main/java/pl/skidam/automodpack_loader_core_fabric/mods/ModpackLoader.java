package pl.skidam.automodpack_loader_core_fabric.mods;

import java.nio.file.Path;
import java.util.List;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.SemanticVersion;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_loader_core_fabric_15.mods.ModpackLoader15;
import pl.skidam.automodpack_loader_core_fabric_16.mods.ModpackLoader16;

@SuppressWarnings("unused")
public class ModpackLoader implements ModpackLoaderService {

	private final ModpackLoaderService dispatcher;

	public ModpackLoader(LoaderManagerService loaderManager) {
		SemanticVersion fabricVersion = SemanticVersion.parse(loaderManager.getLoaderVersion());
		this.dispatcher = fabricVersion.compareTo(SemanticVersion.parse("0.16.1")) >= 0 ? new ModpackLoader16() : new ModpackLoader15();
	}

	@Override
	public void loadModpack(ModpackLoadRequest request) {
		dispatcher.loadModpack(request);
	}

	@Override
	public List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileCache cache) {
		return dispatcher.getModpackNestedConflicts(activeProjectionDirectory, cache);
	}

	@Override
	public boolean discoversNestedConflicts() {
		return true;
	}
}
