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

		// Rules are group-directory-relative: no leading slash, no '/automodpack/host-modpack/<group>/' prefix.
		Pattern hostModpackGroup = Pattern.compile("^/?automodpack/host-modpack/[^/]+/");

		if (config.groups == null) return;
		for (var groupEntry : config.groups.entrySet()) {
			var group = groupEntry.getValue();
			if (group == null) {
				LOGGER.warn("Ignored null group declaration '{}'.", groupEntry.getKey());
				continue;
			}
			Set<String> syncedFiles = new LinkedHashSet<>();
			Set<String> movedExclusions = new LinkedHashSet<>();
			for (String rule : rules(group.syncedFiles)) {
				String path = clean(rule, "syncedFiles");
				if (path == null) continue;
				if (path.startsWith("!")) {
					String exclusion = clean(path.substring(1), "syncedFiles");
					if (exclusion == null) continue;
					LOGGER.info("Moved the exclusion '{}' from syncedFiles to excludedFiles, where exclusions live now.", rule);
					movedExclusions.add(hostModpackGroup.matcher(exclusion).replaceFirst(""));
					continue;
				}
				if (hostModpackGroup.matcher(path).find()) {
					LOGGER.info("Removed redundant syncedFiles entry '{}': the group directory under '/automodpack/host-modpack/' is included in full.", rule);
					continue;
				}
				syncedFiles.add(path);
			}
			group.syncedFiles = syncedFiles;
			group.excludedFiles = normalizeRuleSet(group.excludedFiles, "excludedFiles", hostModpackGroup, movedExclusions);
			group.allowEditsInFiles = normalizeRuleSet(group.allowEditsInFiles, "allowEditsInFiles", hostModpackGroup, Set.of());
		}
	}

	/** Normalizes one rule set: logs away null and blank entries, strips leading slashes and the host-modpack group prefix, and seeds moved exclusions. */
	private static Set<String> normalizeRuleSet(Set<String> ruleSet, String configKey, Pattern hostModpackGroup, Set<String> movedExclusions) {
		Set<String> normalized = new LinkedHashSet<>(movedExclusions);
		for (String rule : rules(ruleSet)) {
			String path = clean(rule, configKey);
			if (path != null) normalized.add(hostModpackGroup.matcher(path).replaceFirst(""));
		}
		return normalized;
	}

	/** Trims one rule and strips its leading slashes; null (logged) when the rule is null or blank. */
	private static String clean(String rule, String configKey) {
		if (rule == null) {
			LOGGER.warn("Ignored null entry in {}.", configKey);
			return null;
		}
		String trimmed = rule.trim();
		if (trimmed.isEmpty()) {
			LOGGER.warn("Ignored empty entry in {}.", configKey);
			return null;
		}
		while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
		return trimmed;
	}

	private static Set<String> rules(Set<String> ruleSet) {
		return ruleSet == null ? Set.of() : ruleSet;
	}
}
