package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.modpack.Modpack;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static pl.skidam.automodpack.StaticVariables.*;

public class ModpackContentTools {
    public static String getFileType(String file, Jsons.ModpackContentFields list) {
        for (Jsons.ModpackContentFields.ModpackContentItems item : list.list) {
            if (item.file.contains(file)) { // compare file absolute path if it contains item.file
                return item.type;
            }
        }
        return "null";
    }

    public static String getModpackLink(String modpack) {
        File modpackDir = getModpackDir(modpack);

        if (!modpackDir.exists() || !modpackDir.isDirectory()) {
            LOGGER.warn("Modpack {} doesn't exist!", modpack);
            return null;
        }

        for (File file : Objects.requireNonNull(modpackDir.listFiles())) {
            if (file.getName().equals(Modpack.hostModpackContentFile.getName())) {
                Jsons.ModpackContentFields modpackContent = ConfigTools.loadConfig(file, Jsons.ModpackContentFields.class);
                assert modpackContent != null;
                if (modpackContent.link != null && !modpackContent.link.equals("")) {
                    return modpackContent.link;
                }
            }
        }
        return null;
    }

    public static File getModpackDir(String modpack) {
        if (modpack == null || modpack.equals("")) {
            LOGGER.warn("Modpack name is null or empty!");
            return null;
        }

        // eg. modpack = 192.168.0.113-30037 `directory`

        return new File(modpacksDir + File.separator + modpack);
    }

    public static Map<String, File> getListOfModpacks() {
        Map<String, File> map = new HashMap<>();
        for (File file : Objects.requireNonNull(modpacksDir.listFiles())) {
            if (file.isDirectory()) {
                map.put(file.getName(), file);
            }
        }
        return map;
    }

    public static File getModpackContentFile(File modpackDir) {
        File[] files = modpackDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals(Modpack.hostModpackContentFile.getName())) {
                    return file;
                }
            }
        }
        return null;
    }

    public static String getStringOfAllHashes(Jsons.ModpackContentFields modpackContent) {
        StringBuilder sb = new StringBuilder();
        for (Jsons.ModpackContentFields.ModpackContentItems item : modpackContent.list) {
            sb.append(item.sha1).append("\n");
        }
        return sb.toString();
    }
}
