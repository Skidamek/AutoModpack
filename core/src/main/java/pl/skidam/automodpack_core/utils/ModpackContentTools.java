package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.config.Jsons;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ModpackContentTools {
    //create Fullserverpack content with files
    public static Jsons.FullServerPackContentFields buildFullServerPackContent(List<Path> files) {
        Set<Jsons.FullServerPackContentFields.FullServerPackContentItem> contentList = new HashSet<>();

        for (Path path : files) {
            try {
                String relative = CustomFileUtils.getPathFromCWD("").relativize(path).toString().replace("\\", "/");
                String file = "/" + relative;
                String size = String.valueOf(Files.size(path));
                String type = getFileExtension(path);
                String sha1 = CustomFileUtils.sha1(path);
                String murmur = "";

                var item = new Jsons.FullServerPackContentFields.FullServerPackContentItem(
                        file, size, type, sha1, murmur
                );

                contentList.add(item);
            } catch (Exception e) {
                LOGGER.warn("Could not process file for FullServerPackContent: {}", path, e);
            }
        }

        return new Jsons.FullServerPackContentFields(contentList);
    }

    public static String getFileType(String file, Jsons.ModpackContentFields list) {
        for (Jsons.ModpackContentFields.ModpackContentItem item : list.list) {
            if (item.file.contains(file)) { // compare file absolute path if it contains item.file
                return item.type;
            }
        }
        return "other";
    }

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
