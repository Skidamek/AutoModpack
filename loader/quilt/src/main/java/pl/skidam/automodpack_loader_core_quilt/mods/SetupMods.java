package pl.skidam.automodpack_loader_core_quilt.mods;


import pl.skidam.automodpack_loader_core.mods.SetupModsService;

import java.nio.file.Path;

@SuppressWarnings({"unchecked", "unused"})
public class SetupMods implements SetupModsService {

    // TODO implement this
    //  maybe use quilt plugins but that's a shame because its experimental and requires manual turning on

    @Override
    public void loadModpack(Path modpack) {
    }

    @Override
    public void removeMod(Path path) {
    }

    @Override
    public void removeMod(String modId) {
    }

    @Override
    public void addMod(Path path) {
    }
}
