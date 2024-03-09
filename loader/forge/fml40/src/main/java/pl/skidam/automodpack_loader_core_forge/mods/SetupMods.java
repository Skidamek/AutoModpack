package pl.skidam.automodpack_loader_core_forge.mods;

import pl.skidam.automodpack_loader_core.mods.SetupModsService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SetupMods implements SetupModsService {

    public static Path modpackPath;
    public static List<Path> modsToRemove = new ArrayList<>();
    public static List<Path> modsToAdd = new ArrayList<>();

    @Override
    public void loadModpack(Path modpack) {
        modpackPath = modpack;
    }

    @Override
    public void removeMod(Path path) {
        modsToRemove.add(path);
    }

    @Override
    public void removeMod(String modId) {

    }

    @Override
    public void addMod(Path path) {
        modsToAdd.add(path);
    }
}
