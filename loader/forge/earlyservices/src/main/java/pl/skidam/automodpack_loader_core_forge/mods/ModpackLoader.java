package pl.skidam.automodpack_loader_core_forge.mods;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import pl.skidam.automodpack_core.loader.ConnectorFallback;
import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileCache;

public class ModpackLoader implements ModpackLoaderService {
	public static final List<Path> modsToLoad = new ArrayList<>();

	@Override
	public Set<String> forceCopyServices() {
		// Forge claims the early-window provider from standard mods/ before mod discovery and runs it in
		// the SERVICE layer - out of reach of anything hosted in the projection - so a mod needing it
		// must be copied to standard mods/, like the fml4 neoforge twin.
		return Set.of(LoaderServicePaths.FORGE_IMMEDIATE_WINDOW_PROVIDER);
	}

	@Override
	public void loadModpack(ModpackLoadRequest request) {
		try {
			for (Path modpackMod : request.modpackMods()) {
				if (FileInspection.isModCompatible(modpackMod)) modsToLoad.add(modpackMod);
			}

			// modsToLoad keeps only loader-compatible jars, so a plain Fabric jar would never reach
			// discovery: Connector is offered the whole pack list here, unlike the neoforge families,
			// which offer only the paths native discovery could not claim (see their EarlyModLocators).
			ConnectorFallback.offer(request.modpackMods());
		} catch (Exception e) {
			LOGGER.error("Error while loading modpack", e);
		}
	}

	@Override
	public List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileCache cache) {
		return new ArrayList<>();
	}
}
