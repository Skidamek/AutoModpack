package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class WorkaroundUtil {

    public final Path modpackPath;
    public final Path workaroundFile;

    public WorkaroundUtil(Path modapckPath) {
        this.modpackPath = modapckPath;
        this.workaroundFile = modpackPath.resolve("workaround.json");
    }

    // returns list of formatted modpack files which are mods with services (these mods need special treatment in order to work properly)
    // mods returned by this method should be installed in standard `~/mods/` directory
    public Set<String> getWorkaroundMods(Jsons.ModpackContentFields modpackContentFields) {
        Set<String> workaroundMods = new HashSet<>();

        // this workaround is needed only for neo/forge
        if (GlobalVariables.LOADER == null || !GlobalVariables.LOADER.contains("forge")) {
            return workaroundMods;
        }

        for (Jsons.ModpackContentFields.ModpackContentItem mod : modpackContentFields.list) {
            if (mod.type.equals("mod")) { // Hmmm?
                Path modPath = CustomFileUtils.getPath(modpackPath, mod.file);
                if (FileInspection.hasSpecificServices(modPath)) {
                    workaroundMods.add(mod.file);
                }
            }
        }

        // add getWorkaroundList to the list of workaround mods (dont add duplicates)
        Set<String> savedWorkaroundMods = getWorkaroundList();
        workaroundMods.addAll(savedWorkaroundMods);

        return workaroundMods;
    }

    // save workaround list to the file using gson from ConfigTools and WorkaroundFields class from Jsons
    public void saveWorkaroundList(Set<String> workaroundMods) {
        Jsons.WorkaroundFields workaroundFields = new Jsons.WorkaroundFields();
        workaroundFields.workaroundMods = workaroundMods;
        ConfigTools.save(workaroundFile, workaroundFields);
    }

    // get workaround list from the file using gson from ConfigTools and WorkaroundFields class from Jsons
    public Set<String> getWorkaroundList() {
        Jsons.WorkaroundFields workaroundFields = ConfigTools.load(workaroundFile, Jsons.WorkaroundFields.class);
        if (workaroundFields == null || workaroundFields.workaroundMods == null) {
            return new HashSet<>();
        }

        int previousWorkaroundVersion = workaroundFields.DO_NOT_CHANGE_IT;
        workaroundFields.DO_NOT_CHANGE_IT = new Jsons.WorkaroundFields().DO_NOT_CHANGE_IT;

        if (previousWorkaroundVersion != workaroundFields.DO_NOT_CHANGE_IT) {
            LOGGER.info("Updated workaround file version to {}", clientConfig.DO_NOT_CHANGE_IT);
        }

        return workaroundFields.workaroundMods;
    }

    public Path getWorkaroundFile() {
        return workaroundFile;
    }
}
