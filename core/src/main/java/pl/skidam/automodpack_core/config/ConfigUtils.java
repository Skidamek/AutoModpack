package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.storage.StoragePaths.SERVER_CONFIG_FILE;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class ConfigUtils {

	/** One completed server-config reload: the config now installed as the global server config and whether hosting must restart. */
	public record ReloadedServerConfig(ServerConfigJsons.ServerConfigFieldsV3 config, boolean connectionSettingsChanged) {}

	/** Reads, normalizes and saves the server config file, then swaps it into the running global; empty when the file is unreadable. */
	public static Optional<ReloadedServerConfig> reloadServerConfig() {
		Optional<ServerConfigJsons.ServerConfigFieldsV3> read = ConfigTools.read(SERVER_CONFIG_FILE, ServerConfigJsons.ServerConfigFieldsV3.class);
		if (read.isEmpty()) return Optional.empty();
		ServerConfigJsons.ServerConfigFieldsV3 config = read.get();
		normalizeServerConfig(config, true);
		boolean connectionSettingsChanged = connectionRuntimeChanged(serverConfig, config);
		serverConfig = config;
		return Optional.of(new ReloadedServerConfig(config, connectionSettingsChanged));
	}

	private static boolean connectionRuntimeChanged(ServerConfigJsons.ServerConfigFieldsV3 previous, ServerConfigJsons.ServerConfigFieldsV3 current) {
		return previous.connectionMode != current.connectionMode || previous.bindPort != current.bindPort || previous.modpackHost != current.modpackHost
				|| previous.disableInternalTLS != current.disableInternalTLS || previous.bandwidthLimit != current.bandwidthLimit
				|| !Objects.equals(previous.bindAddress, current.bindAddress);
	}

	public static ServerConfigJsons.ServerConfigFieldsV3 loadOrCreateServerConfig() {
		ServerConfigJsons.ServerConfigFieldsV3 config = ConfigTools.readOrCreate(SERVER_CONFIG_FILE, ServerConfigJsons.ServerConfigFieldsV3.class, ServerConfigJsons.ServerConfigFieldsV3::new);
		String before = ConfigTools.GSON.toJson(config);
		if (config.acceptedLoaders == null) config.acceptedLoaders = new HashSet<>(Set.of(LOADER));
		else config.acceptedLoaders.add(LOADER);
		normalizeServerConfig(config);
		if (!before.equals(ConfigTools.GSON.toJson(config))) {
			try {
				ConfigTools.writeAtomic(SERVER_CONFIG_FILE, config);
			} catch (IOException e) {
				throw new ConfigTools.ConfigException("Failed to save server configuration", e);
			}
		}
		return config;
	}

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
		if (config.connectionMode == null) config.connectionMode = ModpackConnectionMode.HOLEPUNCH;

		String prefixPattern = "^/?automodpack/host-modpack/[^/]+/";
		Pattern pattern = Pattern.compile(prefixPattern);

		if (config.groups != null) {
			for (var groupEntry : config.groups.entrySet()) {
				var group = groupEntry.getValue();
				if (group == null) {
					LOGGER.warn("Ignored null group declaration '{}'.", groupEntry.getKey());
					continue;
				}
				group.syncedFiles = normalizePathSet(group.syncedFiles, "syncedFiles", pattern, true);
				group.allowEditsInFiles = normalizePathSet(group.allowEditsInFiles, "allowEditsInFiles", pattern, false);
			}
		}
	}

	/**
	 * Trims, prefix-normalizes and logs away broken entries of one path set. {@code dropHostModpackPaths} selects the
	 * syncedFiles rule (paths under '/automodpack/host-modpack/' are implicitly synced, so entries there are removed)
	 * over the path-rules rule (the prefix is stripped from the kept entry).
	 */
	private static Set<String> normalizePathSet(Set<String> files, String configKey, Pattern hostModpackPattern, boolean dropHostModpackPaths) {
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
			if (dropHostModpackPaths) {
				if (hostModpackPattern.matcher(trimmed).find()) {
					LOGGER.info("Removed redundant {} entry '{}': paths under '/automodpack/host-modpack/' are implicitly synced.", configKey, file);
					continue;
				}
				normalizedFiles.add(prefixSlash(file));
			} else {
				var fixed = hostModpackPattern.matcher(trimmed).replaceFirst("");
				if (!fixed.equals(trimmed)) {
					LOGGER.info("Normalized {} entry: '{}' -> '{}'. Removed '/automodpack/host-modpack/' prefix.", configKey, file, fixed);
				}
				normalizedFiles.add(prefixSlash(fixed));
			}
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
