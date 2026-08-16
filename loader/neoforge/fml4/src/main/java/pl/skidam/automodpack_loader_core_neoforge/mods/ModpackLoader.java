package pl.skidam.automodpack_loader_core_neoforge.mods;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

public class ModpackLoader implements ModpackLoaderService {
	private static final String CONNECTOR_MODS_PROPERTY = "connector.additionalModLocations";
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

	public static void configureConnectorFallback(List<Path> paths) {
		String configuredPaths = paths.stream().map(Path::toString).collect(Collectors.joining(","));
		String existingPaths = System.getProperty(CONNECTOR_MODS_PROPERTY, "");
		String finalPaths = configuredPaths.isEmpty() ? existingPaths : existingPaths.isEmpty() ? configuredPaths : configuredPaths + "," + existingPaths;
		if (!finalPaths.isEmpty()) System.setProperty(CONNECTOR_MODS_PROPERTY, finalPaths);
	}

	@Override
	public List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileMetadataCache cache) {
		return new ArrayList<>();
	}
}
