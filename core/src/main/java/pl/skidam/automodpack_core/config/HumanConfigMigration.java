package pl.skidam.automodpack_core.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.OsPaths;

/** One-shot JSON / legacy `.conf` -> current human model. Used only when the canonical {@code .toml} is missing. */
public final class HumanConfigMigration {
	private static final Logger LOGGER = LogManager.getLogger(HumanConfigMigration.class);
	private static final String DEFAULT_LEGACY_CATEGORY = "General";

	private HumanConfigMigration() {}

	private static final List<String> DROPPED_SERVER = List.of("forceCopyFilesToStandardLocation", "nonModpackFilesToDelete", "updateIpsOnEveryStart", "autoExcludeUnnecessaryFiles");
	private static final List<String> DROPPED_CLIENT = List.of("selectedModpack", "installedModpacks", "allowRemoteNonModpackDeletions");

	public static ServerConfigJsons.ServerConfigFieldsV3 mapServer(JsonObject json) {
		ServerConfigJsons.ServerConfigFieldsV3 config = isV4Server(json) ? mapV4Server(json) : mapV5Server(json);
		applyTopLevelExclusion(config, json);
		return config;
	}

	private static void applyTopLevelExclusion(ServerConfigJsons.ServerConfigFieldsV3 config, JsonObject json) {
		JsonElement element = first(json, "auto-exclude-server-side-mods", "autoExcludeServerSideMods");
		if (element == null) return;
		if (!element.isJsonPrimitive()) throw new ConfigTools.ConfigParseException("auto-exclude-server-side-mods must be a boolean or mode name, got " + element);
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) config.modpack.autoExcludeServerSideMods = primitive.getAsBoolean() ? ServerSideModExclusion.METADATA : ServerSideModExclusion.DISABLED;
		else config.modpack.autoExcludeServerSideMods = ConfigTools.enumConstant(ServerSideModExclusion.class, primitive.getAsString());
	}

	public static MappedClient mapClient(JsonObject json) {
		ClientConfigJsons.ClientConfigFieldsV3 config = new ClientConfigJsons.ClientConfigFieldsV3();
		copyBoolean(json, config, "updateSelectedModpackOnLaunch", "update-selected-modpack-on-launch");
		copyBoolean(json, config, "selfUpdater", "self-updater");
		copyBoolean(json, config, "syncAutoModpackVersion", "sync-automodpack-version");
		copyBoolean(json, config, "syncLoaderVersion", "sync-loader-version", "sync-versions");
		copyBoolean(json, config, "playMusic", "play-music");
		copyBoolean(json, config, "showModpackSettingsButton", "show-modpack-settings-button");
		List<String> pins = stringList(first(json, "pinnedModIds", "pinned-mod-ids"));
		if (pins != null) config.pinnedModIds = pins;
		for (String key : DROPPED_CLIENT) if (json.has(key)) LOGGER.info("Dropping obsolete client config key {}", key);
		String follow = string(first(json, "selectedModpackId", "selected-modpack-id"));
		if (follow != null && !follow.isBlank() && ModpackId.isValid(follow)) return new MappedClient(config, follow);
		return new MappedClient(config, "");
	}

	public record MappedClient(ClientConfigJsons.ClientConfigFieldsV3 config, String followId) {}

	private static boolean isV4Server(JsonObject json) {
		if (json.has("modpack") && json.get("modpack").isJsonObject()) return false;
		return json.has("modpackName") || json.has("syncedFiles") || json.has("requireAutoModpackOnClient");
	}

	private static ServerConfigJsons.ServerConfigFieldsV3 mapV4Server(JsonObject json) {
		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		copySameNameServer(json, config);
		String name = string(json.get("modpackName"));
		if (name != null) config.modpack.name = name;
		if (json.has("requireAutoModpackOnClient")) config.requireModpack = json.get("requireAutoModpackOnClient").getAsBoolean();
		String address = string(json.get("addressToSend"));
		if (address != null) config.advertisedEndpointHost = address;
		if (json.has("portToSend") && json.get("portToSend").isJsonPrimitive()) config.advertisedEndpointPort = json.get("portToSend").getAsInt();
		config.connectionMode = mapV4ConnectionMode(json, config.bindPort);
		ServerConfigJsons.GroupDeclaration main = config.modpack.groups.computeIfAbsent("main", ignored -> new ServerConfigJsons.GroupDeclaration());
		main.name = "Core";
		main.description = "Core modpack files";
		main.selection = GroupSelectionMode.REQUIRED;
		List<String> synced = stringList(json.get("syncedFiles"));
		if (synced != null) main.fromServer = stripLeadingSlashes(synced);
		List<String> editable = stringList(json.get("allowEditsInFiles"));
		if (editable != null) config.modpack.editable = stripLeadingSlashes(editable);
		boolean skipJunk = json.has("autoExcludeUnnecessaryFiles") && !json.get("autoExcludeUnnecessaryFiles").getAsBoolean();
		config.modpack.exclude = skipJunk ? new LinkedHashSet<>() : new LinkedHashSet<>(ServerConfigJsons.FACTORY_JUNK_EXCLUDE);
		logDropped(json, DROPPED_SERVER);
		return config;
	}

	private static ModpackConnectionMode mapV4ConnectionMode(JsonObject json, int bindPort) {
		if (!json.has("requireMagicPackets") || !json.get("requireMagicPackets").isJsonPrimitive()) {
			ModpackConnectionMode existing = connectionMode(json);
			return existing == null ? ModpackConnectionMode.HOLEPUNCH : existing;
		}
		boolean requireMagic = json.get("requireMagicPackets").getAsBoolean();
		if (!requireMagic && bindPort != -1) return ModpackConnectionMode.HTTP;
		return ModpackConnectionMode.HOLEPUNCH;
	}

	private static ServerConfigJsons.ServerConfigFieldsV3 mapV5Server(JsonObject json) {
		ServerConfigJsons.ServerConfigFieldsV3 config = new ServerConfigJsons.ServerConfigFieldsV3();
		copySameNameServer(json, config);
		if (json.has("syncLoaderVersion") && !json.has("advertiseVersionsToSync") && !json.has("advertise-versions-to-sync"))
			config.advertiseVersionsToSync = json.get("syncLoaderVersion").getAsBoolean();
		if (json.has("modpackName") && (config.modpack.name == null || config.modpack.name.isEmpty())) {
			String leftover = string(json.get("modpackName"));
			if (leftover != null) config.modpack.name = leftover;
		}
		JsonElement modpack = json.get("modpack");
		if (modpack != null && modpack.isJsonObject()) {
			config.modpack = mapModpack(modpack.getAsJsonObject(), config.modpack.name);
		} else {
			// v5-rc groups declared their own exclude/editable lists rather than inheriting pack-wide defaults.
			config.modpack.exclude = Set.of();
			config.modpack.editable = Set.of();
		}
		ModpackConnectionMode mode = connectionMode(json);
		if (mode != null) config.connectionMode = mode;
		logDropped(json, DROPPED_SERVER);
		return config;
	}

	private static void copySameNameServer(JsonObject json, ServerConfigJsons.ServerConfigFieldsV3 config) {
		copyBoolean(json, "modpackHost", "modpack-host", v -> config.modpackHost = v);
		copyBoolean(json, "generateModpackOnStart", "generate-modpack-on-start", v -> config.generateModpackOnStart = v);
		copyBoolean(json, "requireModpack", "require-modpack", v -> config.requireModpack = v);
		copyBoolean(json, "nagUnModdedClients", "nag-un-modded-clients", v -> config.nagUnModdedClients = v);
		String nagMessage = string(first(json, "nagMessage", "nag-message"));
		if (nagMessage != null) config.nagMessage = nagMessage;
		String nagClickableMessage = string(first(json, "nagClickableMessage", "nag-clickable-message"));
		if (nagClickableMessage != null) config.nagClickableMessage = nagClickableMessage;
		String nagClickableLink = string(first(json, "nagClickableLink", "nag-clickable-link"));
		if (nagClickableLink != null) config.nagClickableLink = nagClickableLink;
		String bindAddress = string(first(json, "bindAddress", "bind-address"));
		if (bindAddress != null) config.bindAddress = bindAddress;
		copyInt(json, "bindPort", "bind-port", v -> config.bindPort = v);
		String advertisedHost = string(first(json, "advertisedEndpointHost", "advertised-endpoint-host"));
		if (advertisedHost != null) config.advertisedEndpointHost = advertisedHost;
		copyInt(json, "advertisedEndpointPort", "advertised-endpoint-port", v -> config.advertisedEndpointPort = v);
		copyBoolean(json, "disableInternalTLS", "disable-internal-tls", v -> config.disableInternalTLS = v);
		copyBoolean(json, "acceptProxyProtocol", "accept-proxy-protocol", v -> config.acceptProxyProtocol = v);
		copyInt(json, "bandwidthLimit", "bandwidth-limit", v -> config.bandwidthLimit = v);
		copyBoolean(json, "validateSecrets", "validate-secrets", v -> config.validateSecrets = v);
		copyLong(json, "secretLifetime", "secret-lifetime", v -> config.secretLifetime = v);
		String exportHttpDirectory = string(first(json, "exportHttpDirectory", "export-http-directory"));
		if (exportHttpDirectory != null) config.exportHttpDirectory = exportHttpDirectory;
		copyBoolean(json, "exportHttpIncludeAll", "export-http-include-all", v -> config.exportHttpIncludeAll = v);
		copyBoolean(json, "selfUpdater", "self-updater", v -> config.selfUpdater = v);
		List<String> loaders = stringList(first(json, "acceptedLoaders", "accepted-loaders"));
		if (loaders != null) config.acceptedLoaders = new LinkedHashSet<>(loaders);
		copyBoolean(json, "advertiseVersionsToSync", "advertise-versions-to-sync", v -> config.advertiseVersionsToSync = v);
	}

	private static ServerConfigJsons.ModpackFields mapModpack(JsonObject json, String existingName) {
		ServerConfigJsons.ModpackFields fields = new ServerConfigJsons.ModpackFields();
		fields.exclude = Set.of();
		fields.editable = Set.of();
		String name = string(json.get("name"));
		fields.name = name != null ? name : existingName;

		Map<String, Map<String, ServerConfigJsons.GroupDeclaration>> legacyCategories = new LinkedHashMap<>();
		Map<String, ServerConfigJsons.GroupDeclaration> uncategorized = new LinkedHashMap<>();
		for (var entry : json.entrySet()) {
			if (entry.getKey().equals("name") || !entry.getValue().isJsonObject()) continue;
			JsonObject object = entry.getValue().getAsJsonObject();
			if (looksLikeGroup(object)) {
				uncategorized.put(entry.getKey(), mapGroup(object, entry.getKey()));
			} else {
				Map<String, ServerConfigJsons.GroupDeclaration> groups = new LinkedHashMap<>();
				for (var group : object.entrySet()) {
					if (group.getValue().isJsonObject()) groups.put(group.getKey(), mapGroup(group.getValue().getAsJsonObject(), group.getKey()));
				}
				if (!groups.isEmpty()) legacyCategories.put(entry.getKey(), groups);
			}
		}

		boolean hasCustomCategory = legacyCategories.keySet().stream().anyMatch(cat -> !cat.equals(DEFAULT_LEGACY_CATEGORY));
		Set<String> usedCategoryIds = new HashSet<>();
		for (var catEntry : legacyCategories.entrySet()) {
			String rawCategory = catEntry.getKey();
			Map<String, ServerConfigJsons.GroupDeclaration> groups = catEntry.getValue();
			boolean flatten = (rawCategory.equals(DEFAULT_LEGACY_CATEGORY) && !hasCustomCategory)
					|| groups.values().stream().noneMatch(g -> g.selection != GroupSelectionMode.REQUIRED);
			String categoryId = "";
			if (!flatten) {
				categoryId = slugifyCategoryId(rawCategory, usedCategoryIds);
				ServerConfigJsons.CategoryDeclaration cat = new ServerConfigJsons.CategoryDeclaration();
				cat.name = rawCategory;
				fields.categories.put(categoryId, cat);
			}
			for (var grpEntry : groups.entrySet()) {
				ServerConfigJsons.GroupDeclaration g = grpEntry.getValue();
				if (!categoryId.isEmpty() && g.selection != GroupSelectionMode.REQUIRED) g.category = categoryId;
				fields.groups.put(grpEntry.getKey(), g);
			}
		}
		fields.groups.putAll(uncategorized);

		if (fields.groups.isEmpty()) {
			fields.groups.put("main", new ServerConfigJsons.GroupDeclaration());
		}
		return fields;
	}

	private static String slugifyCategoryId(String rawName, Set<String> usedIds) {
		String lower = rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^[-_]+|[-_]+$", "");
		String base = lower.isEmpty() ? "category" : lower;
		if (!Character.isLetterOrDigit(base.charAt(0))) base = "c-" + base;
		if (OsPaths.isReservedWindowsDeviceName(base)) base = "c-" + base;
		if (base.length() > 64) base = base.substring(0, 64);
		String candidate = base;
		for (int suffix = 2; usedIds.contains(candidate) || OsPaths.isReservedWindowsDeviceName(candidate); suffix++) {
			String tag = "-" + suffix;
			int maxBase = Math.max(1, 64 - tag.length());
			candidate = (base.length() > maxBase ? base.substring(0, maxBase) : base) + tag;
		}
		usedIds.add(candidate);
		return candidate;
	}

	private static boolean looksLikeGroup(JsonObject object) {
		return object.has("from-server") || object.has("fromServer") || object.has("syncedFiles") || object.has("exclude") || object.has("excludedFiles")
				|| object.has("editable") || object.has("allowEditsInFiles") || object.has("displayName") || object.has("display-name") || object.has("required")
				|| object.has("defaultSelected") || object.has("default-selected") || object.has("selection") || object.has("breaksWith") || object.has("breaks-with")
				|| object.has("requires") || object.has("compatiblePlatforms") || object.has("compatible-platforms") || object.has("description")
				|| object.has("auto-exclude-server-side-mods") || object.has("autoExcludeServerSideMods");
	}

	private static ServerConfigJsons.GroupDeclaration mapGroup(JsonObject json, String groupId) {
		ServerConfigJsons.GroupDeclaration group = new ServerConfigJsons.GroupDeclaration();
		String displayName = string(first(json, "displayName", "display-name", "name"));
		if (displayName != null) group.name = displayName;
		String description = string(json.get("description"));
		if (description != null) group.description = description;
		if (json.has("selection")) group.selection = selectionEnum(json.get("selection"));
		else if (json.has("required") || json.has("defaultSelected") || json.has("default-selected")) group.selection = GroupSelectionMigration.fromLegacyPair(json, "Group '" + groupId + "'");
		JsonElement exclusion = first(json, "auto-exclude-server-side-mods", "autoExcludeServerSideMods");
		if (exclusion != null) group.autoExcludeServerSideMods = exclusionEnum(exclusion);
		List<String> breaksWith = stringList(first(json, "breaksWith", "breaks-with"));
		if (breaksWith != null && !breaksWith.isEmpty())
			LOGGER.info("Dropping obsolete 'breaks-with' on group '{}' ({}); use an exclusive category ([categories.<id>] pick = \"AT_MOST_ONE\" or \"EXACTLY_ONE\") for mutual exclusion", groupId, breaksWith);
		List<String> requires = stringList(json.get("requires"));
		if (requires != null) group.requires = new LinkedHashSet<>(requires);
		List<String> platforms = stringList(first(json, "compatiblePlatforms", "compatible-platforms"));
		if (platforms != null) group.compatiblePlatforms = new LinkedHashSet<>(platforms);
		List<String> fromServer = stringList(first(json, "from-server", "fromServer", "syncedFiles"));
		if (fromServer != null) group.fromServer = stripLeadingSlashes(fromServer);
		List<String> exclude = stringList(first(json, "exclude", "excludedFiles"));
		if (exclude != null) group.exclude = stripLeadingSlashes(exclude);
		List<String> editable = stringList(first(json, "editable", "allowEditsInFiles"));
		if (editable != null) group.editable = stripLeadingSlashes(editable);
		return group;
	}

	/** The selection key must be a string naming a mode - Gson would otherwise hand a null or an object to a raw runtime exception instead of the parse failure. */
	private static GroupSelectionMode selectionEnum(JsonElement element) {
		if (!element.isJsonPrimitive()) throw new ConfigTools.ConfigParseException("Group selection must be a string value, got " + element);
		return ConfigTools.enumConstant(GroupSelectionMode.class, element.getAsString());
	}

	private static ServerSideModExclusion exclusionEnum(JsonElement element) {
		if (!element.isJsonPrimitive()) throw new ConfigTools.ConfigParseException("auto-exclude-server-side-mods must be a string value, got " + element);
		return ConfigTools.enumConstant(ServerSideModExclusion.class, element.getAsString());
	}

	private static Set<String> stripLeadingSlashes(List<String> rules) {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		for (String rule : rules) {
			if (rule == null) continue;
			boolean negated = rule.startsWith("!");
			String body = negated ? rule.substring(1) : rule;
			while (body.startsWith("/")) body = body.substring(1);
			out.add((negated ? "!" : "") + body);
		}
		return out;
	}

	private static ModpackConnectionMode connectionMode(JsonObject json) {
		JsonElement element = first(json, "connectionMode", "connection-mode");
		if (element == null || !element.isJsonPrimitive()) return null;
		String raw = element.getAsString();
		if ("DIRECT".equals(raw)) return ModpackConnectionMode.HTTP;
		return ConfigTools.enumConstant(ModpackConnectionMode.class, raw);
	}

	private static void copyBoolean(JsonObject json, ClientConfigJsons.ClientConfigFieldsV3 config, String... names) {
		JsonElement element = first(json, names);
		if (element == null || !element.isJsonPrimitive()) return;
		boolean value = element.getAsBoolean();
		switch (names[0]) {
			case "updateSelectedModpackOnLaunch" -> config.updateSelectedModpackOnLaunch = value;
			case "selfUpdater" -> config.selfUpdater = value;
			case "syncAutoModpackVersion" -> config.syncAutoModpackVersion = value;
			case "syncLoaderVersion" -> config.syncVersions = value;
			case "playMusic" -> config.playMusic = value;
			case "showModpackSettingsButton" -> config.showModpackSettingsButton = value;
			default -> {
			}
		}
	}

	private interface BooleanSink {
		void accept(boolean value);
	}

	private interface IntSink {
		void accept(int value);
	}

	private interface LongSink {
		void accept(long value);
	}

	private static void copyBoolean(JsonObject json, String camel, String kebab, BooleanSink sink) {
		JsonElement element = first(json, camel, kebab);
		if (element != null && element.isJsonPrimitive()) sink.accept(element.getAsBoolean());
	}

	private static void copyInt(JsonObject json, String camel, String kebab, IntSink sink) {
		JsonElement element = first(json, camel, kebab);
		if (element != null && element.isJsonPrimitive()) sink.accept(element.getAsInt());
	}

	private static void copyLong(JsonObject json, String camel, String kebab, LongSink sink) {
		JsonElement element = first(json, camel, kebab);
		if (element != null && element.isJsonPrimitive()) sink.accept(element.getAsLong());
	}

	private static JsonElement first(JsonObject json, String... names) {
		for (String name : names) if (json.has(name)) return json.get(name);
		return null;
	}

	private static String string(JsonElement element) {
		if (element == null || element.isJsonNull()) return null;
		if (element.isJsonPrimitive()) return element.getAsString();
		return null;
	}

	private static List<String> stringList(JsonElement element) {
		if (element == null || element.isJsonNull()) return null;
		if (!element.isJsonArray()) return null;
		JsonArray array = element.getAsJsonArray();
		List<String> out = new ArrayList<>();
		for (JsonElement item : array) {
			if (item != null && item.isJsonPrimitive()) {
				JsonPrimitive primitive = item.getAsJsonPrimitive();
				if (primitive.isString()) out.add(primitive.getAsString());
			}
		}
		return out;
	}

	private static void logDropped(JsonObject json, List<String> keys) {
		for (String key : keys) if (json.has(key)) LOGGER.info("Dropping obsolete server config key {}", key);
	}
}
