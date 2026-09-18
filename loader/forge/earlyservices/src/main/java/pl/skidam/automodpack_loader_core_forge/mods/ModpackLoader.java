package pl.skidam.automodpack_loader_core_forge.mods;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import pl.skidam.automodpack_core.loader.ConnectorFallback;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileCache;

public class ModpackLoader implements ModpackLoaderService {
	public static final List<Path> modsToLoad = new ArrayList<>();

	// No override of forceCopyServices(): this Forge generation can host every service it handles
	// in place (see EarlyServiceLayer), so nothing forces a copy.

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
