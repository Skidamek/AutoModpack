package pl.skidam.automodpack_core.config;

import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;

/**
 * Translates the legacy pack-level {@code auto-exclude-server-side-mods} boolean onto each group's
 * {@link ServerSideModExclusion} enum during migration from v4 JSON and v5-rc {@code .conf}.
 */
final class ServerSideExclusionMigration {
	static final String KEY = "auto-exclude-server-side-mods";
	private static final String LEGACY_CAMEL = "autoExcludeServerSideMods";

	private ServerSideExclusionMigration() {}

	/** Pack-level boolean when the file still has one; null when the key is already gone. */
	static Boolean packLevelEnabled(JsonObject document) {
		JsonElement element = first(document, KEY, LEGACY_CAMEL);
		if (element == null) return null;
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
			throw new ConfigTools.ConfigParseException("auto-exclude-server-side-mods must be a boolean, got " + element);
		return element.getAsBoolean();
	}

	/** Stamps {@code DISABLED} onto migrated groups that omitted the key when pack-level exclusion was false. */
	static void applyToMapped(ServerConfigJsons.ServerConfigFieldsV3 config, JsonObject original) {
		Boolean enabled = packLevelEnabled(original);
		if (enabled == null || enabled) return;
		Set<String> explicit = new HashSet<>();
		ServerConfigJsons.forEachGroup(original, (category, group, declaration) -> {
			if (declaration.has(KEY) || declaration.has(LEGACY_CAMEL)) explicit.add(category + "/" + group);
		});
		for (var category : config.modpack.categories.entrySet()) {
			if (category.getValue() == null) continue;
			for (var group : category.getValue().entrySet()) {
				if (group.getValue() == null || explicit.contains(category.getKey() + "/" + group.getKey())) continue;
				group.getValue().autoExcludeServerSideMods = ServerSideModExclusion.DISABLED;
			}
		}
	}

	private static JsonElement first(JsonObject json, String... names) {
		for (String name : names) if (json.has(name)) return json.get(name);
		return null;
	}
}
