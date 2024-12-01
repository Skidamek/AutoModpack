package pl.skidam.automodpack_loader_core_neoforge.mods;

import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.GlobalVariables.MODS_DIR;

public class ModpackLoader implements ModpackLoaderService {
    public static String CONNECTOR_MODS_PROPERTY = "connector.additionalModLocations";
    public static List<Path> modsToAdd = new ArrayList<>();

    @Override
    public void loadModpack(List<Path> modpackMods) {
        try {
            List<Path> onlyMods = new ArrayList<>();
            modpackMods.stream().filter(p -> {
                boolean ends = p.toString().endsWith(".jar");
                boolean parentMods = p.getParent().toAbsolutePath().normalize().toString().equals(MODS_DIR.toAbsolutePath().normalize().toString());
                boolean isFile = p.toFile().isFile();

                return ends && parentMods && isFile;
            }).forEach(onlyMods::add);

            modsToAdd.addAll(onlyMods);
            // set for connector
            String paths = onlyMods.stream().map(Path::toString).collect(Collectors.joining(","));
            String finalMods = paths + "," + System.getProperty(CONNECTOR_MODS_PROPERTY, "");
            System.setProperty(CONNECTOR_MODS_PROPERTY, finalMods);
        } catch (Exception e) {
            LOGGER.error("Error while loading modpack", e);
        }
    }

    @Override
    public List<LoaderManagerService.Mod> getModpackNestedConflicts(Path modpackDir, Set<String> ignoredMods) {
        return new ArrayList<>();
    }
}
