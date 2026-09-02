package pl.skidam.automodpack_loader_core_neoforge.mods;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public class ModpackLoader implements ModpackLoaderService {
	public static final List<Path> modsToLoad = new ArrayList<>();

	@Override
	public Set<String> forceCopyServices() {
		// NeoForge picks the early-window provider and creates the window in the same call, before
		// and out of reach of anything we can do from the active projection - a mod needing it must be
		// copied to standard mods/.
		return Set.of(LoaderServicePaths.NEOFORGE_IMMEDIATE_WINDOW_PROVIDER);
	}

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
	public List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileMetadataCache cache) {
		return new ArrayList<>();
	}
}
