package pl.skidam.automodpack_core.mods;

import java.nio.file.Path;

public class SetupMods implements SetupModsService {
    @Override
    public void run(Path modpack) {
        throw new AssertionError("Loader class not found");
    }
}
