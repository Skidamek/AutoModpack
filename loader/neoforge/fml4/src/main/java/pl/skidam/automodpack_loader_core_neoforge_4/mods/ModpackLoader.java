package pl.skidam.automodpack_loader_core_neoforge_4.mods;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;

public class ModpackLoader implements ModpackLoaderService {
	private static final Logger LOGGER = LogManager.getLogger(ModpackLoader.class);

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
			modsToLoad.addAll(request.modpackMods().stream().map(Path::toAbsolutePath).map(Path::normalize).distinct().sorted().filter(FileInspection::isMod).toList());
		} catch (Exception e) {
			LOGGER.error("Error while loading modpack", e);
		}
	}
}
