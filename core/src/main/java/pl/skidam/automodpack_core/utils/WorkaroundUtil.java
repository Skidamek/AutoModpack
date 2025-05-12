package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.Jsons;

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
    public Set<String> getWorkaroundMods(Jsons.ModpackContentFields modpackContentFields) {
        Set<String> workaroundMods = new HashSet<>();

        // this workaround is needed only for neo/forge mods
        if (GlobalVariables.LOADER == null || !GlobalVariables.LOADER.contains("forge")) {
            return workaroundMods;
        }

        for (Jsons.ModpackContentFields.ModpackContentItem mod : modpackContentFields.list) {
            if (mod.type.equals("mod")) {
                Path modPath = CustomFileUtils.getPath(modpackPath, mod.file);
                if (FileInspection.hasSpecificServices(modPath)) {
                    workaroundMods.add(mod.file);
                }
            }
        }

        return workaroundMods;
    }
}
