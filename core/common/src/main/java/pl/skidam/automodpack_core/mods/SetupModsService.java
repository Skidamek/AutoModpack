package pl.skidam.automodpack_core.mods;

import java.nio.file.Path;

public interface SetupModsService {
    void loadModpack(Path modpack);
    void removeMod(Path path);
    void addMod(Path path);
}
