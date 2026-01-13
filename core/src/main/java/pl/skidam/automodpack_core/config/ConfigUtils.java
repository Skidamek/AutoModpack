package pl.skidam.automodpack_core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ConfigUtils {

    public static void normalizeServerConfig(Jsons.ServerConfigFieldsV2 config, boolean saveAfter) {
        normalizeServerConfig(config);
        if (saveAfter) {
            ConfigTools.save(serverConfigFile, config);
        }
    }

    public static void normalizeServerConfig(Jsons.ServerConfigFieldsV2 config) {
        List<String> fixedSyncedFiles = new ArrayList<>(config.syncedFiles.size());
        List<String> fixedAllowEditsInFiles = new ArrayList<>(config.allowEditsInFiles.size());
        List<String> fixedForceCopyFilesToStandardLocation = new ArrayList<>(config.forceCopyFilesToStandardLocation.size());

        String prefixPattern = "^/automodpack/host-modpack/[^/]+/";
        Pattern pattern = Pattern.compile(prefixPattern);

        for (var file : config.syncedFiles) {
            if (file == null) {
                LOGGER.warn("Ignored null entry in syncedFiles.");
                continue;
            }
            var trimmed = file.trim();
            if (trimmed.isEmpty()) {
                LOGGER.warn("Ignored empty entry in syncedFiles.");
                continue;
            }
            if (pattern.matcher(trimmed).find()) {
                LOGGER.info("Removed redundant syncedFiles entry '{}': paths under '/automodpack/host-modpack/' are implicitly synced.", file);
            } else {
                fixedSyncedFiles.add(prefixSlash(file));
            }
        }

        for (var file : config.allowEditsInFiles) {
            if (file == null) {
                LOGGER.warn("Ignored null entry in allowEditsInFiles.");
                continue;
            }
            var trimmed = file.trim();
            if (trimmed.isEmpty()) {
                LOGGER.warn("Ignored empty entry in allowEditsInFiles.");
                continue;
            }
            var fixed = pattern.matcher(trimmed).replaceFirst("");
            if (!fixed.equals(trimmed)) {
                LOGGER.info("Normalized allowEditsInFiles entry: '{}' -> '{}'. Removed '/automodpack/host-modpack/' prefix.", file, fixed);
            }
            fixedAllowEditsInFiles.add(prefixSlash(fixed));
        }

        for (var file : config.forceCopyFilesToStandardLocation) {
            if (file == null) {
                LOGGER.warn("Ignored null entry in forceCopyFilesToStandardLocation.");
                continue;
            }
            var trimmed = file.trim();
            if (trimmed.isEmpty()) {
                LOGGER.warn("Ignored empty entry in forceCopyFilesToStandardLocation.");
                continue;
            }
            var fixed = pattern.matcher(trimmed).replaceFirst("");
            if (!fixed.equals(trimmed)) {
                LOGGER.info("Normalized forceCopyFilesToStandardLocation entry: '{}' -> '{}'. Removed '/automodpack/host-modpack/' prefix.", file, fixed);
            }
            fixedForceCopyFilesToStandardLocation.add(prefixSlash(fixed));
        }

        config.syncedFiles = fixedSyncedFiles;
        config.allowEditsInFiles = fixedAllowEditsInFiles;
        config.forceCopyFilesToStandardLocation = fixedForceCopyFilesToStandardLocation;
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