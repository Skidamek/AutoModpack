package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;
import pl.skidam.reconf.ConfigPath;
import pl.skidam.reconf.Document;

/**
 * The pack-level {@code auto-exclude-server-side-mods} boolean each group now carries as an enum. A file that still
 * has the pack-level key is translated on the way into the model and again into the document on the next save.
 */
public final class ServerSideExclusionMigration implements ConfMigration {
	static final String KEY = "auto-exclude-server-side-mods";
	private static final String LEGACY_CAMEL = "autoExcludeServerSideMods";
	private static final String PACK_SECTION = "modpack";

	public static final ServerSideExclusionMigration INSTANCE = new ServerSideExclusionMigration();

	private ServerSideExclusionMigration() {}

	/** Pack-level boolean when the file still has one; null when the key is already gone. */
	static Boolean packLevelEnabled(JsonObject document) {
		JsonElement element = first(document, KEY, LEGACY_CAMEL);
		if (element == null) return null;
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
			throw new ConfigTools.ConfigParseException("auto-exclude-server-side-mods must be a boolean, got " + element);
		return element.getAsBoolean();
	}

	/** Pack-level {@code false} stamps {@code DISABLED} onto groups that omit the key, then drops the pack-level keys. */
	static void migrateDocument(JsonObject document) {
		Boolean enabled = packLevelEnabled(document);
		if (enabled == null) return;
		if (!enabled)
			ConfMigration.forEachGroup(document, (category, group, declaration) -> {
				if (!declaration.has(KEY) && !declaration.has(LEGACY_CAMEL)) declaration.addProperty(KEY, ServerSideModExclusion.DISABLED.name());
			});
		document.remove(KEY);
		document.remove(LEGACY_CAMEL);
	}

	/**
	 * JSON predecessors may create groups the source tree never named (v4 had none). Stamp those from the pack-level
	 * flag before {@link #migrateDocument} drops it.
	 */
	static void applyToMapped(ServerConfigJsons.ServerConfigFieldsV3 config, JsonObject original) {
		Boolean enabled = packLevelEnabled(original);
		if (enabled == null || enabled) return;
		Set<String> explicit = new HashSet<>();
		ConfMigration.forEachGroup(original, (category, group, declaration) -> {
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

	@Override
	public boolean appliesTo(Class<?> type) {
		return type == ServerConfigJsons.ServerConfigFieldsV3.class;
	}

	@Override
	public void forRead(JsonObject document) {
		migrateDocument(document);
	}

	@Override
	public void repair(Document document, Path path) {
		JsonObject tree = ReconfConfigs.toJsonObject(document.tree());
		if (!tree.has(KEY) && !tree.has(LEGACY_CAMEL)) return;
		migrateDocument(tree);
		ConfMigration.forEachGroup(tree, (category, group, declaration) -> {
			if (!declaration.has(KEY)) return;
			document.ensure(ConfigPath.of(PACK_SECTION, category, group).appended(KEY), ReconfConfigs.toJsonValue(declaration.get(KEY)), null);
		});
		document.remove(ConfigPath.of(KEY));
		document.remove(ConfigPath.of(LEGACY_CAMEL));
		LOGGER.info("Moved pack-level '{}' onto groups in {}", KEY, path.getFileName());
	}

	private static JsonElement first(JsonObject json, String... names) {
		for (String name : names) if (json.has(name)) return json.get(name);
		return null;
	}
}
