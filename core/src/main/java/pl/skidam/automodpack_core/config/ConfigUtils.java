package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.*;

import java.util.*;
import java.util.regex.Pattern;

public class ConfigUtils {

	public static void normalizeServerConfig(Jsons.ServerConfigFieldsV2 config, boolean saveAfter) {
		normalizeServerConfig(config);
		if (saveAfter) ConfigTools.save(serverConfigFile, config);
	}

	public static void normalizeServerConfig(Jsons.ServerConfigFieldsV2 config) {
		Set<String> fixedSyncedFiles = new LinkedHashSet<>(config.syncedFiles.size());
		Map<String, String> fixedNonModpackFilesToDelete = new LinkedHashMap<>(config.nonModpackFilesToDelete.size());

		String prefixPattern = "^/?automodpack/host-modpack/[^/]+/";
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

		Set<String> fixedAllowEditsInFiles = normalizePathRules(config.allowEditsInFiles, "allowEditsInFiles", pattern);
		Set<String> fixedOverwriteEditableFiles = normalizePathRules(config.overwriteEditableFiles, "overwriteEditableFiles", pattern);
		Set<String> fixedForceCopyFilesToStandardLocation = normalizePathRules(config.forceCopyFilesToStandardLocation, "forceCopyFilesToStandardLocation", pattern);

		for (var entry : config.nonModpackFilesToDelete.entrySet()) {
			var file = entry.getKey();
			var hash = entry.getValue();
			if (file == null) {
				LOGGER.warn("Ignored null key in nonModpackFilesToDelete.");
				continue;
			}
			var trimmed = file.trim();
			if (trimmed.isEmpty()) {
				LOGGER.warn("Ignored empty key in nonModpackFilesToDelete.");
				continue;
			}
			var fixed = pattern.matcher(trimmed).replaceFirst("");
			if (!fixed.equals(trimmed)) {
				LOGGER.info("Normalized nonModpackFilesToDelete entry: '{}' -> '{}'. Removed '/automodpack/host-modpack/' prefix.", file, fixed);
			}
			fixedNonModpackFilesToDelete.put(prefixSlash(fixed), hash);
		}

		config.syncedFiles = fixedSyncedFiles;
		config.allowEditsInFiles = fixedAllowEditsInFiles;
		config.overwriteEditableFiles = fixedOverwriteEditableFiles;
		config.forceCopyFilesToStandardLocation = fixedForceCopyFilesToStandardLocation;
		config.nonModpackFilesToDelete = fixedNonModpackFilesToDelete;
	}

	private static Set<String> normalizePathRules(Set<String> files, String configKey, Pattern hostModpackPattern) {
		if (files == null || files.isEmpty()) return new LinkedHashSet<>();

		Set<String> normalizedFiles = new LinkedHashSet<>(files.size());
		for (var file : files) {
			if (file == null) {
				LOGGER.warn("Ignored null entry in {}.", configKey);
				continue;
			}
			var trimmed = file.trim();
			if (trimmed.isEmpty()) {
				LOGGER.warn("Ignored empty entry in {}.", configKey);
				continue;
			}
			var fixed = hostModpackPattern.matcher(trimmed).replaceFirst("");
			if (!fixed.equals(trimmed)) {
				LOGGER.info("Normalized {} entry: '{}' -> '{}'. Removed '/automodpack/host-modpack/' prefix.", configKey, file, fixed);
			}
			normalizedFiles.add(prefixSlash(fixed));
		}
		return normalizedFiles;
	}

	public static String prefixSlash(String path) {
		if (path == null) return null;
		if (path.isEmpty()) return path;
		if (path.startsWith("/!/")) return path.substring(1);
		if (path.startsWith("/")) return path;
		if (path.startsWith("!/")) return path;
		if (path.charAt(0) == '!') return "!/" + path.substring(1);
		return "/" + path;
	}
}
