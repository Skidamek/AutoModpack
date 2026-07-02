package pl.skidam.automodpack_loader_core_forge.mods;

import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

import pl.skidam.automodpack_loader_core_forge.EarlyServiceLayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public class ModpackLoader implements ModpackLoaderService {
    public static String CONNECTOR_MODS_PROPERTY = "connector.additionalModLocations";
    public static List<Path> modsToLoad = new ArrayList<>();

    @Override
    public Set<String> inPlaceHandleableServices() {
        // Single source of truth, shared with the bootstrapper: the candidate/dependency locators
        // run in place, a transformation service's lifecycle is forwarded, and language providers
        // are picked up from the GAME layer - so a mod shipping only these never needs copying.
        return EarlyServiceLayer.HANDLEABLE_SERVICES;
    }

    @Override
    public Set<String> knownServices() {
        // The exact set THIS Forge version promotes to the SERVICE layer early. Lets the copy
        // decision ignore services this version doesn't handle - a copy wouldn't help them.
        return EarlyServiceLayer.knownServices();
    }

    @Override
    public void loadModpack(List<Path> modpackMods) {
        try {
            for (Path modpackMod : modpackMods) {
                if (FileInspection.isModCompatible(modpackMod)) {
                    modsToLoad.add(modpackMod);
                }
            }

            // set for connector
            String paths = modpackMods.stream().map(Path::toString).collect(Collectors.joining(","));
            String finalMods = paths + "," + System.getProperty(CONNECTOR_MODS_PROPERTY, "");
            System.setProperty(CONNECTOR_MODS_PROPERTY, finalMods);
        } catch (Exception e) {
            LOGGER.error("Error while loading modpack", e);
        }
    }

    @Override
    public List<FileInspection.Mod> getModpackNestedConflicts(Path modpackDir, FileMetadataCache cache) {
        return new ArrayList<>();
    }
}
