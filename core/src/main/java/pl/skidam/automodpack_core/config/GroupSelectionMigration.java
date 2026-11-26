package pl.skidam.automodpack_core.config;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;

/**
 * Translates the legacy {@code required} / {@code default-selected} group flag pair (v5-rc.1 and JSON
 * predecessors) into {@link GroupSelectionMode}.
 */
final class GroupSelectionMigration {
	private static final Logger LOGGER = LogManager.getLogger(GroupSelectionMigration.class);
	private static final List<String> LEGACY_DEFAULT_SELECTED = List.of("defaultSelected", "default-selected");

	private GroupSelectionMigration() {}

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
}
