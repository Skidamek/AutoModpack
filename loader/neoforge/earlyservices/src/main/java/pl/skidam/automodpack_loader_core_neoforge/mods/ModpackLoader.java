package pl.skidam.automodpack_loader_core_neoforge.mods;

import pl.skidam.automodpack_core.loader.LoaderServicePaths;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

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
    public Set<String> forceCopyServices() {
        // NeoForge picks the early-window provider and creates the window in the same call, before
        // and out of reach of anything we can do from the modpack folder - a mod needing it must be
        // copied to standard mods/.
        return Set.of(LoaderServicePaths.NEOFORGE_IMMEDIATE_WINDOW_PROVIDER);
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
