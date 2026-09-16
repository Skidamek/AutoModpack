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
		// Seeded on first load only; an admin-edited set is honored as-is, including one that dropped this server's loader.
		if (config.acceptedLoaders == null || config.acceptedLoaders.isEmpty()) config.acceptedLoaders = new HashSet<>(Set.of(LOADER));
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

		// Rules are group-directory-relative: no leading slash, no '/automodpack/host-modpack/<this group>' prefix (slash optional).
		if (config.modpack == null) return;
		for (var categoryEntry : config.modpack.entrySet()) {
			var category = categoryEntry.getValue();
			if (category == null) throw new ConfigTools.ConfigParseException("Category '" + categoryEntry.getKey() + "' is null; declare its groups or remove the category");
			for (var groupEntry : category.entrySet()) {
				var group = groupEntry.getValue();
				if (group == null) throw new ConfigTools.ConfigParseException("Group '" + groupEntry.getKey() + "' in category '" + categoryEntry.getKey() + "' is null; declare it or remove the entry");
				Pattern ownGroupPrefix = Pattern.compile("^/?automodpack/host-modpack/" + Pattern.quote(groupEntry.getKey()) + "(?:/|$)");
				group.syncedFiles = normalizeRuleSet(group.syncedFiles, "syncedFiles", groupEntry.getKey(), ownGroupPrefix, true);
				group.excludedFiles = normalizeRuleSet(group.excludedFiles, "excludedFiles", groupEntry.getKey(), ownGroupPrefix, false);
				group.allowEditsInFiles = normalizeRuleSet(group.allowEditsInFiles, "allowEditsInFiles", groupEntry.getKey(), ownGroupPrefix, false);
			}
		}
	}

	/**
	 * Normalizes one rule set: logs away null and blank entries, strips leading slashes and the host-modpack group
	 * prefix. A '!' rule stays put and keeps its set-local meaning: in syncedFiles it excepts the path from the synced
	 * set only - the group directory may still provide it - while excludedFiles keeps it from clients entirely, so
	 * moving one to the other would change what ships. syncedFiles entries under the group directory are dropped
	 * instead of stripped: the directory is included in full, so a synced rule there can only be redundant.
	 */
	private static Set<String> normalizeRuleSet(Set<String> ruleSet, String configKey, String groupId, Pattern ownGroupPrefix, boolean dropOwnGroupPaths) {
		Set<String> normalized = new LinkedHashSet<>();
		for (String rule : rules(ruleSet)) {
			String path = clean(rule, configKey);
			if (path == null) continue;
			boolean negated = path.startsWith("!");
			String body = negated ? path.substring(1) : path;
			while (body.startsWith("/")) body = body.substring(1);
			if (body.startsWith("automodpack/host-modpack/")) {
				if (ownGroupPrefix.matcher(body).find()) {
					if (dropOwnGroupPaths) LOGGER.info("Removed redundant {} entry '{}': the group directory under '/automodpack/host-modpack/' is included in full.", configKey, rule);
					else {
						String remainder = ownGroupPrefix.matcher(body).replaceFirst("");
						while (remainder.startsWith("/")) remainder = remainder.substring(1);
						while (remainder.contains("**/**")) remainder = remainder.replace("**/**", "**");
						if (remainder.isBlank() || remainder.equals("**") || remainder.equals("**/*"))
							LOGGER.warn("Ignored {} entry '{}': a whole-directory host-modpack rule would also match every synced path.", configKey, rule);
						else
							normalized.add((negated ? "!" : "") + remainder);
					}
				} else {
					// Another group's directory cannot be spelled in this group's relative space; stripping it would
					// silently rebind the rule to this group's files. Keep it verbatim and tell the server owner.
					LOGGER.warn("Kept {} entry '{}' verbatim: host-modpack rules must name their own group '{}'.", configKey, rule, groupId);
					normalized.add((negated ? "!" : "") + body);
				}
				continue;
			}
			normalized.add((negated ? "!" : "") + body);
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
		trimmed = trimmed.replace('\\', '/');
		while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
		return trimmed;
	}

	private static Set<String> rules(Set<String> ruleSet) {
		return ruleSet == null ? Set.of() : ruleSet;
	}
}
