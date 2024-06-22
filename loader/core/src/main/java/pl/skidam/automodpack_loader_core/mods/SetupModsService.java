package pl.skidam.automodpack_loader_core.mods;

import java.nio.file.Path;

public interface SetupModsService {
    void loadModpack(Path modpack);
    void addMod(Path path);
}
