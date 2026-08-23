package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_CONFIG_FILE;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class ConfigUtils {

	public static void normalizeServerConfig(ServerConfigJsons.ServerConfigFieldsV3 config, boolean saveAfter) {
		normalizeServerConfig(config);
		if (saveAfter) {
			try {
				ConfigTools.writeAtomic(SERVER_CONFIG_FILE, config);
			} catch (IOException e) {
				throw new ConfigTools.ConfigException("Failed to save server configuration", e);
			}
		}
	}

	public static void normalizeServerConfig(ServerConfigJsons.ServerConfigFieldsV3 config) {
		if (config.connectionMode == null) config.connectionMode = ModpackConnectionMode.defaultFor();

		String prefixPattern = "^/?automodpack/host-modpack/[^/]+/";
		Pattern pattern = Pattern.compile(prefixPattern);

		if (config.groups != null) {
			for (var groupEntry : config.groups.entrySet()) {
				var group = groupEntry.getValue();
				if (group == null) {
					LOGGER.warn("Ignored null group declaration '{}'.", groupEntry.getKey());
					continue;
				}
				group.syncedFiles = normalizeSyncedFiles(group.syncedFiles, pattern);
				group.allowEditsInFiles = normalizePathRules(group.allowEditsInFiles, "allowEditsInFiles", pattern);
				group.overwriteEditableFiles = normalizePathRules(group.overwriteEditableFiles, "overwriteEditableFiles", pattern);
			}
		}
	}

	private static Set<String> normalizeSyncedFiles(Set<String> syncedFiles, Pattern hostModpackPattern) {
		if (syncedFiles == null || syncedFiles.isEmpty()) return new LinkedHashSet<>();

		Set<String> fixedSyncedFiles = new LinkedHashSet<>(syncedFiles.size());
		for (var file : syncedFiles) {
			if (file == null) {
				LOGGER.warn("Ignored null entry in syncedFiles.");
				continue;
			}
			var trimmed = file.trim();
			if (trimmed.isEmpty()) {
				LOGGER.warn("Ignored empty entry in syncedFiles.");
				continue;
			}
			if (hostModpackPattern.matcher(trimmed).find()) {
				LOGGER.info("Removed redundant syncedFiles entry '{}': paths under '/automodpack/host-modpack/' are implicitly synced.", file);
			} else {
				fixedSyncedFiles.add(prefixSlash(file));
			}
		}
		return fixedSyncedFiles;
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

	private static String prefixSlash(String path) {
		if (path == null) return null;
		if (path.isEmpty()) return path;
		if (path.startsWith("/!/")) return path.substring(1);
		if (path.startsWith("/")) return path;
		if (path.startsWith("!/")) return path;
		if (path.charAt(0) == '!') return "!/" + path.substring(1);
		return "/" + path;
	}
}
