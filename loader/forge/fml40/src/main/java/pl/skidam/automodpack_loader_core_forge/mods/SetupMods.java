package pl.skidam.automodpack_loader_core_forge.mods;

import pl.skidam.automodpack_loader_core.mods.SetupModsService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class SetupMods implements SetupModsService {
    public static String CONNECTOR_MODS_PROPERTY = "connector.additionalModLocations";
    public static Path modpackPath;
    public static List<Path> modsToAdd = new ArrayList<>();

    @Override
    public void loadModpack(Path modpack) {
        modpackPath = modpack;

        if (modpack == null || !Files.isDirectory(modpack)) {
            LOGGER.warn("Incorrect path to modpack");
            return;
        }

        modpack = modpack.toAbsolutePath();

        try {
            List<Path> pathList = Files.list(modpack).toList();
            for (Path path : pathList) {
                if (!Files.isDirectory(path)) {
                    continue;
                }

                if (!path.getFileName().toString().equals("mods")) {
                    continue;
                }

                List<Path> modsList = Files.list(path).toList();
                modsToAdd.addAll(modsList);
                // set for connector
                String paths = modsList.stream().map(Path::toString).collect(Collectors.joining(","));
                String finalMods = paths + "," + System.getProperty(CONNECTOR_MODS_PROPERTY, "");
                System.setProperty(CONNECTOR_MODS_PROPERTY, finalMods);
            }
        } catch (Exception e) {
            LOGGER.error("Error while loading modpack", e);
        }
    }

    @Override
    public void addMod(Path path) {
        if (modsToAdd.contains(path)) {
            return;
        }
        modsToAdd.add(path);
    }
}
