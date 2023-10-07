package pl.skidam.automodpack_core.mods;

import java.nio.file.Path;

public interface SetupModsService {
    void run(Path modpack);
}
