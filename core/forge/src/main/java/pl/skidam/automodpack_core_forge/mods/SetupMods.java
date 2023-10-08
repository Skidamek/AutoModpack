package pl.skidam.automodpack_core_forge.mods;

import pl.skidam.automodpack_core.mods.SetupModsService;

import java.nio.file.Path;

public class SetupMods implements SetupModsService {
    @Override
    public void loadModpack(Path modpack) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public void removeMod(Path path) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public void addMod(Path path) {
        throw new AssertionError("Loader class not found");
    }
}
