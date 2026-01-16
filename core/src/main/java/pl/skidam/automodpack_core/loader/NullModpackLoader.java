package pl.skidam.automodpack_core.loader;

import java.nio.file.Path;
import java.util.List;

public class NullModpackLoader implements ModpackLoaderService {

    @Override
    public void loadModpack(List<Path> modpackMods) {
        throw new AssertionError("Loader class not found");
    }
}
