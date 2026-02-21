package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class WorkaroundUtil {

    public final Path modpackPath;

    public WorkaroundUtil(Path modapckPath) {
        this.modpackPath = modapckPath;
    }

    // returns list of formatted modpack files which are mods with services (these mods need special treatment in order to work properly)
    // mods returned by this method should be installed in standard `~/mods/` directory
    public Set<String> getWorkaroundMods(Jsons.ModpackContent ModpackContent) throws IOException {
        Set<String> workaroundMods = new HashSet<>();

        // this workaround is needed only for neo/forge mods
        if (Constants.LOADER == null || !Constants.LOADER.contains("forge")) {
            return workaroundMods;
        }

        for (var entry : ModpackContent.groups.entrySet()) {
            Jsons.ModpackGroupFields group = entry.getValue();
            for (Jsons.ModpackContentItem item : group.files) { // TODO loop though the installed groups
                if (item.type.equals("mod")) {
                    Path modPath = SmartFileUtils.getPath(modpackPath, item.file);
                    if (!Files.exists(modPath)) continue;
                    try (FileSystem fs = FileSystems.newFileSystem(modPath)) {
                        if (FileInspection.hasSpecificServices(fs)) {
                            workaroundMods.add(item.file);
                        }
                    }
                }
            }
        }

        return workaroundMods;
    }
}