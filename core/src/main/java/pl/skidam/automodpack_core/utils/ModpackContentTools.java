package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import pl.skidam.automodpack_core.config.Jsons;

public class ModpackContentTools {

    public static Optional<Path> getModpackDir(String modpack) {
        if (modpack == null || modpack.isEmpty()) {
            return Optional.empty();
        }

        // eg. modpack = /automodpack/modpacks/TestPack `directory`

        return Optional.of(modpacksDir.resolve(modpack));
    }

    public static Optional<Path> getModpackContentFile(Path modpackDir) {
        if (!Files.exists(modpackDir)) {
            return Optional.empty();
        }

        Path path = modpackDir.getParent().resolve(hostModpackContentFile.getFileName()); // server
        if (!Files.exists(path)) {
            path = modpackDir.resolve(hostModpackContentFile.getFileName()); // client
            if (!Files.exists(path)) {
                return Optional.empty();
            }
        }

        return Optional.of(path);
    }
}
