package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ModpackContentTools {
    public static String getFileType(String file, Jsons.ModpackContentFields list) {
        for (Jsons.ModpackContentFields.ModpackContentItem item : list.list) {
            if (item.file.contains(file)) { // compare file absolute path if it contains item.file
                return item.type;
            }
        }
        return "other";
    }

    public static Optional<String> getModpackLink(Path modpackDir) {
        if (modpackDir == null) {
            throw new IllegalArgumentException("Modpack dir cannot be null or empty!");
        }

        Path path = modpackDir.resolve(hostModpackContentFile.getFileName());

        Jsons.ModpackContentFields modpackContent = ConfigTools.loadConfig(path, Jsons.ModpackContentFields.class);
        if (modpackContent != null && modpackContent.link != null && !modpackContent.link.isEmpty()) {
            return Optional.of(modpackContent.link);
        }

        return Optional.empty();
    }

    public static Optional<Path> getModpackDir(String modpack) {
        if (modpack == null || modpack.isEmpty()) {
            return Optional.empty();
        }

        // eg. modpack = /automodpack/modpacks/TestPack `directory`

        return Optional.of(Paths.get(modpacksDir + File.separator + modpack));
    }

    public static Optional<Path> getModpackContentFile(Path modpackDir) {
        if (!Files.exists(modpackDir)) {
            return Optional.empty();
        }

        try {
            for (Path path : Files.list(modpackDir).toList()) {
                if (Objects.equals(path.getFileName(), hostModpackContentFile.getFileName())) {
                    return Optional.of(path);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }


        return Optional.empty();
    }
}
