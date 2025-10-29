package pl.skidam.automodpack_core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ConfigUtils {

    public static void normalizeServerConfig(boolean saveAfter) {
        normalizeServerConfig();
        if (saveAfter) {
            ConfigTools.save(serverConfigFile, serverConfig);
        }
    }

    public static void normalizeServerConfig() {
        List<String> fixedSyncedFiles = new ArrayList<>(serverConfig.syncedFiles.size());
        List<String> fixedAllowEditsInFiles = new ArrayList<>(serverConfig.allowEditsInFiles.size());

        String prefixPattern = "^/automodpack/host-modpack/[^/]+/";
        Pattern pattern = Pattern.compile(prefixPattern);

        for (var file : serverConfig.syncedFiles) {
            if (pattern.matcher(file).find()) {
                LOGGER.info("Removed redundant syncedFiles entry '{}': paths under '/automodpack/host-modpack/' are implicitly synced.", file);
            } else {
                fixedSyncedFiles.add(file);
            }
        }

        for (var file : serverConfig.allowEditsInFiles) {
            var matcher = pattern.matcher(file);
            if (matcher.find()) {
                String fixed = matcher.replaceFirst("");
                LOGGER.info("Normalized allowEditsInFiles entry: '{}' -> '{}'. Removed '/automodpack/host-modpack/' prefix.", file, fixed);
                fixedAllowEditsInFiles.add(fixed);
            } else {
                fixedAllowEditsInFiles.add(file);
            }
        }

        serverConfig.syncedFiles = fixedSyncedFiles;
        serverConfig.allowEditsInFiles = fixedAllowEditsInFiles;
    }
}
