package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.reconf.ConfigPath;
import pl.skidam.reconf.Document;

/**
 * The group selection flags the {@code selection} key replaced on the human surface. A hand edit that wrote the legacy
 * pair is translated rather than destroyed, and a file that already says {@code selection} keeps that word: the
 * migration runs on the way into the model and again into the document on the next save, so the two paths cannot
 * disagree about which one wins. It also names the pack section whose groups it owns, which is what lets the generic
 * store walk those groups without knowing what a modpack is.
 */
public final class GroupSelectionMigration implements ConfMigration {
	/** Every spelling the replaced flags answer to; a hand edit may use either, and both mean the same word. */
	private static final List<String> LEGACY_DEFAULT_SELECTED = List.of("defaultSelected", "default-selected");
	private static final List<String> LEGACY_KEYS = List.of("required", "defaultSelected", "default-selected");

	private static final String SELECTION = "selection";

	public static final GroupSelectionMigration INSTANCE = new GroupSelectionMigration();

	private GroupSelectionMigration() {}

	/**
	 * The mode a legacy flag pair means, read from whichever spelling the file used. This is the one rule: the JSON
	 * migration and the reconf repair both ask it, so they cannot disagree about what an old config meant, and the one
	 * pair the modes cannot express says so in the log instead of being rewritten in silence.
	 */
	static GroupSelectionMode fromLegacyPair(JsonObject declaration, String context) {
		boolean required = ConfigTools.strictBoolean(declaration, "required", context);
		boolean defaultSelected = strictBoolean(declaration, LEGACY_DEFAULT_SELECTED, context);
		GroupSelectionMode mode = GroupSelectionMode.of(required, defaultSelected);
		if (GroupSelectionMode.collapsed(required, defaultSelected)) LOGGER.info("{} wrote required with default-selected false, which the selection modes cannot express; it became {}", context, mode);
		return mode;
	}

	private static boolean strictBoolean(JsonObject declaration, List<String> keys, String context) {
		for (String key : keys) if (declaration.has(key)) return ConfigTools.strictBoolean(declaration, key, context);
		return false;
	}

	private static boolean holdsLegacyPair(JsonObject declaration) {
		for (String key : LEGACY_KEYS) if (declaration.has(key)) return true;
		return false;
	}

	@Override
	public boolean appliesTo(Class<?> type) {
		return type == ServerConfigJsons.ServerConfigFieldsV3.class;
	}

	@Override
	public void forRead(JsonObject document) {
		ServerConfigJsons.forEachGroup(document, (category, group, declaration) -> migrate(declaration, context(category, group)));
	}

	@Override
	public void repair(Document document, Path path) {
		ServerConfigJsons.forEachGroup(ReconfConfigs.toJsonObject(document.tree()), (category, group, declaration) -> {
			if (!migrate(declaration, context(category, group))) return;
			ConfigPath groupPath = ConfigPath.of(ServerConfigJsons.PACK_SECTION, category, group);
			// The value lands in the document on its own account, with no comment of its own: a data migration must never
			// ride on an editorial annotation surviving. ensure leaves a member that is already there alone.
			document.ensure(groupPath.appended(SELECTION), ReconfConfigs.toJsonValue(declaration.get(SELECTION)), null);
			for (String key : LEGACY_KEYS) document.remove(groupPath.appended(key));
			LOGGER.info("Migrated the selection flags of group '{}' in category '{}' to 'selection' in {}", group, category, path.getFileName());
		});
	}

	@Override
	public List<Shape> nestedShapes(Object model) {
		if (!(model instanceof ServerConfigJsons.ServerConfigFieldsV3 server) || server.modpack == null || server.modpack.categories == null) return List.of();
		List<Shape> shapes = new ArrayList<>();
		for (var category : server.modpack.categories.entrySet()) {
			if (category.getValue() == null) continue;
			for (var group : category.getValue().entrySet())
				if (group.getValue() != null) shapes.add(new Shape(ConfigPath.of(ServerConfigJsons.PACK_SECTION, category.getKey(), group.getKey()), group.getValue()));
		}
		return shapes;
	}

	/** Translates one declaration's legacy pair into {@code selection}, dropping the pair either way; false when it held none. */
	private static boolean migrate(JsonObject declaration, String context) {
		if (!holdsLegacyPair(declaration)) return false;
		if (!declaration.has(SELECTION)) declaration.addProperty(SELECTION, fromLegacyPair(declaration, context).name());
		for (String key : LEGACY_KEYS) declaration.remove(key);
		return true;
	}

	private static String context(String category, String group) {
		return "Group '" + group + "' in category '" + category + "'";
	}
}
