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
			throw new ConfigTools.ConfigParseException("auto-exclude-server-side-mods must be a boolean or a group enum, got " + element);
		return element.getAsBoolean();
	}

	/** Group paths ({@code category/id}) that already name the new key, so a pack-level {@code false} does not overwrite them. */
	static Set<String> explicitGroups(JsonObject document) {
		Set<String> explicit = new HashSet<>();
		forEachGroup(document, (category, group, declaration) -> {
			if (declaration.has(KEY) || declaration.has(LEGACY_CAMEL)) explicit.add(path(category, group));
		});
		return explicit;
	}

	/** Stamps {@code DISABLED} onto groups that omit the key when the pack-level flag is false, then drops the pack-level keys. */
	static void migrateDocument(JsonObject document) {
		Boolean enabled = packLevelEnabled(document);
		if (enabled != null && !enabled)
			forEachGroup(document, (category, group, declaration) -> {
				if (!declaration.has(KEY) && !declaration.has(LEGACY_CAMEL)) declaration.addProperty(KEY, ServerSideModExclusion.DISABLED.name());
			});
		document.remove(KEY);
		document.remove(LEGACY_CAMEL);
	}

	/** Same rule for a model already mapped from JSON: groups that never wrote the key become {@code DISABLED} when the pack-level flag is false. */
	static void applyToMapped(ServerConfigJsons.ServerConfigFieldsV3 config, JsonObject original) {
		Boolean enabled = packLevelEnabled(original);
		if (enabled == null || enabled) return;
		Set<String> explicit = explicitGroups(original);
		for (var category : config.modpack.categories.entrySet()) {
			if (category.getValue() == null) continue;
			for (var group : category.getValue().entrySet()) {
				if (group.getValue() == null || explicit.contains(path(category.getKey(), group.getKey()))) continue;
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
		if (!document.has(KEY) && !document.has(LEGACY_CAMEL)) return;
		migrateDocument(document);
	}

	@Override
	public void repair(Document document, Path path) {
		JsonObject tree = ReconfConfigs.toJsonObject(document.tree());
		if (!tree.has(KEY) && !tree.has(LEGACY_CAMEL)) return;
		document.remove(ConfigPath.of(KEY));
		document.remove(ConfigPath.of(LEGACY_CAMEL));
		LOGGER.info("Moved pack-level '{}' onto groups in {}", KEY, path.getFileName());
	}

	private static void forEachGroup(JsonObject document, GroupVisitor visitor) {
		if (!(document.get(PACK_SECTION) instanceof JsonObject modpack)) return;
		for (var category : modpack.entrySet()) {
			if (category.getKey().equals("name") || !(category.getValue() instanceof JsonObject categoryObject)) continue;
			for (var group : categoryObject.entrySet()) if (group.getValue() instanceof JsonObject groupObject) visitor.visit(category.getKey(), group.getKey(), groupObject);
		}
	}

	private static JsonElement first(JsonObject json, String... names) {
		for (String name : names) if (json.has(name)) return json.get(name);
		return null;
	}

	private static String path(String category, String group) {
		return category + "/" + group;
	}

	private interface GroupVisitor {
		void visit(String category, String group, JsonObject declaration);
	}
}
