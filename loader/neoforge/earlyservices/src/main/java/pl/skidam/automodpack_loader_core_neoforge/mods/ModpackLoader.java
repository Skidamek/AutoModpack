package pl.skidam.automodpack_loader_core_neoforge.mods;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileCache;

public class ModpackLoader implements ModpackLoaderService {
	public static final List<Path> modsToLoad = new ArrayList<>();

	@Override
	public void loadModpack(ModpackLoadRequest request) {
		try {
			List<Path> stagedMods = request.modpackMods().stream().filter(FileInspection::isMod).toList();
			modsToLoad.addAll(stagedMods);
		} catch (Exception e) {
			LOGGER.error("Error while loading modpack", e);
		}
	}

	@Override
	public List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileCache cache) {
		return new ArrayList<>();
	}
}
