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
        List<String> fixedForceCopyFilesToStandardLocation = new ArrayList<>(serverConfig.forceCopyFilesToStandardLocation.size());

        String prefixPattern = "^/automodpack/host-modpack/[^/]+/";
        Pattern pattern = Pattern.compile(prefixPattern);

        for (var file : serverConfig.syncedFiles) {
            if (pattern.matcher(file).find()) {
                LOGGER.info("Removed redundant syncedFiles entry '{}': paths under '/automodpack/host-modpack/' are implicitly synced.", file);
            } else {
                fixedSyncedFiles.add(prefixSlash(file));
            }
        }

        for (var file : serverConfig.allowEditsInFiles) {
            var matcher = pattern.matcher(file);
            var fixed = file;
            if (matcher.find()) {
                fixed = matcher.replaceFirst("");
                LOGGER.info("Normalized allowEditsInFiles entry: '{}' -> '{}'. Removed '/automodpack/host-modpack/' prefix.", file, fixed);
            }
            fixedAllowEditsInFiles.add(prefixSlash(fixed));
        }

        for (var file : serverConfig.forceCopyFilesToStandardLocation) {
            var matcher = pattern.matcher(file);
            var fixed = file;
            if (matcher.find()) {
                fixed = matcher.replaceFirst("");
                LOGGER.info("Normalized forceCopyFilesToStandardLocation entry: '{}' -> '{}'. Removed '/automodpack/host-modpack/' prefix.", file, fixed);
            }
            fixedForceCopyFilesToStandardLocation.add(prefixSlash(fixed));
        }

        serverConfig.syncedFiles = fixedSyncedFiles;
        serverConfig.allowEditsInFiles = fixedAllowEditsInFiles;
        serverConfig.forceCopyFilesToStandardLocation = fixedForceCopyFilesToStandardLocation;
    }

    public static String prefixSlash(String path) {
        if (path == null) {
            return null;
        }
        if (path.isEmpty()) {
            return path;
        }
        if (path.startsWith("/!/")) {
            return path.substring(1);
        }
        if (path.startsWith("/")) {
            return path;
        }
        if (path.startsWith("!/")) {
            return path;
        }
        if (path.charAt(0) == '!') {
            return "!/" + path.substring(1);
        }
        return "/" + path;
    }
}
