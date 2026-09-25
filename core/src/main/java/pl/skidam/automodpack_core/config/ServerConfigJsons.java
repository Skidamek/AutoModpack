package pl.skidam.automodpack_core.config;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class ServerConfigJsons {

	public static class ServerConfigFieldsV3 {
		@ReconfConfigs.Comment("file version - do not change")
		public int DO_NOT_CHANGE_IT = 3;
		@ReconfConfigs.Comment("serve the modpack to clients from this server")
		public boolean modpackHost = true;
		@ReconfConfigs.Comment("scan and regenerate the modpack on every server start")
		public boolean generateModpackOnStart = true;
		// Category name -> group id -> declaration. The group id is referenced by requires/breaksWith and by the client's saved selection; the category name is the player-facing section label.
		@ReconfConfigs.Comment("what clients receive. name is the pack's display name; host-modpack/<group>/ ships in full, syncedFiles pulls from the server root, excludedFiles keeps files off clients (put server-only mods there)")
		public ModpackFields modpack = ModpackFields.withMainGroup();
		@ReconfConfigs.Comment("leave mods marked as server-side out of the synced modpack")
		public boolean autoExcludeServerSideMods = true;
		@ReconfConfigs.Comment("require clients to install the modpack")
		public boolean requireModpack = true;
		@ReconfConfigs.Comment("show a message to players joining without the mod")
		public boolean nagUnModdedClients = true;
		@ReconfConfigs.Comment("message shown to unmodded players")
		public String nagMessage = "Install the AutoModpack mod to get this server's modpack!";
		@ReconfConfigs.Comment("text of the clickable nag message")
		public String nagClickableMessage = "Click here to get the AutoModpack!";
		@ReconfConfigs.Comment("link the nag message opens")
		public String nagClickableLink = "https://modrinth.com/project/automodpack";
		@ReconfConfigs.Comment("address the modpack host binds to; empty = all interfaces")
		public String bindAddress = "";
		@ReconfConfigs.Comment("port the modpack host binds to; -1 = same as the server port")
		public int bindPort = -1;
		@ReconfConfigs.Comment("host advertised to clients; empty = automatic")
		public String advertisedEndpointHost = "";
		@ReconfConfigs.Comment("port advertised to clients; -1 = same as the bind port")
		public int advertisedEndpointPort = -1;
		@ReconfConfigs.Comment("disable the built-in TLS listener")
		public boolean disableInternalTLS = false;
		@ReconfConfigs.Comment("honor HAProxy PROXY protocol headers; enable only behind a trusted proxy")
		public boolean acceptProxyProtocol = false;
		@ReconfConfigs.Comment("HOLEPUNCH, MAGIC or HTTP; HOLEPUNCH carries the pack through the Minecraft server port")
		public ModpackConnectionMode connectionMode = ModpackConnectionMode.HOLEPUNCH;
		@ReconfConfigs.Comment("per-client transfer cap in MiB/s; 0 = unlimited")
		public int bandwidthLimit = 0;
		@ReconfConfigs.Comment("require clients to hold a server-issued secret")
		public boolean validateSecrets = true;
		@ReconfConfigs.Comment("secret lifetime in hours; 336 = 14 days")
		public long secretLifetime = 336;
		@ReconfConfigs.Comment("export the URL-contract tree (head, journal, objects/) to this directory after every publish for any static HTTPS host; empty = off")
		public String exportHttpDirectory = "";
		@ReconfConfigs.Comment("include every object in the HTTP export even when Modrinth or CurseForge serves it; keeps the host as a backstop for link rot")
		public boolean exportHttpIncludeAll = false;
		@ReconfConfigs.Comment("let the mod update itself")
		public boolean selfUpdater = false;
		@ReconfConfigs.Comment("loaders a client may run the modpack with; seeded once, then yours to edit")
		public Set<String> acceptedLoaders = new HashSet<>();
		@ReconfConfigs.Comment("publish the pack's loader and Minecraft versions so clients match them; off = files-only pack")
		public boolean advertiseVersionsToSync = true;
	}

	/**
	 * Defaults for a fresh standalone host config: no game handshake exists to provision secrets, and nothing is synced from the host directory unless the admin adds rules. Values land in the file once and are the
	 * admin's to change.
	 */
	public static ServerConfigFieldsV3 standalone() {
		ServerConfigFieldsV3 config = new ServerConfigFieldsV3();
		config.validateSecrets = false;
		config.modpack = ModpackFields.withStandaloneMain();
		return config;
	}

	// Default group for a fresh config. The sets are linked so a generated file is byte-identical across runs (§7.4 determinism).
	private static GroupDeclaration mainGroupDeclaration() {
		GroupDeclaration declaration = new GroupDeclaration();
		declaration.description = "Core modpack files";
		declaration.required = true;
		declaration.defaultSelected = true;
		declaration.syncedFiles = new LinkedHashSet<>(List.of("mods/*.jar", "kubejs/**", "emotes/*"));
		declaration.excludedFiles = new LinkedHashSet<>(List.of(".*", ".*/**", "**/.*", "**/.*/**", "*.tmp", "**/*.tmp", "*.disabled", "**/*.disabled", "*.bak", "**/*.bak", "kubejs/server_scripts/**"));
		declaration.allowEditsInFiles = new LinkedHashSet<>(List.of("options.txt", "config/**"));
		return declaration;
	}

	/**
	 * The pack section: the reserved {@code name} key is the pack's display name, every other member is a category of
	 * groups. The reserved key is what makes this a typed object instead of a bare map - a {@code name} member that is
	 * not a string fails the parse with a clear message instead of being silently swallowed as an unread category.
	 */
	public static class ModpackFields {
		public String name = "";
		public Map<String, Map<String, GroupDeclaration>> categories = new LinkedHashMap<>();

		private static final Type GROUPS_TYPE = new TypeToken<Map<String, GroupDeclaration>>() {
		}.getType();

		public static ModpackFields withMainGroup() {
			ModpackFields fields = new ModpackFields();
			fields.categories = new LinkedHashMap<>(Map.of("General", new LinkedHashMap<>(Map.of("main", mainGroupDeclaration()))));
			return fields;
		}

		public static ModpackFields withStandaloneMain() {
			ModpackFields fields = withMainGroup();
			fields.categories.get("General").get("main").syncedFiles = Set.of();
			return fields;
		}

		/** Serializes {@code name} plus every category into one flat object and reads the same shape back. */
		public static final class Adapter implements JsonSerializer<ModpackFields>, JsonDeserializer<ModpackFields>, ConfigTools.UnknownKeyScanner {
			@Override
			public JsonElement serialize(ModpackFields src, Type type, JsonSerializationContext context) {
				JsonObject object = new JsonObject();
				object.addProperty("name", src.name);
				for (var entry : src.categories.entrySet()) object.add(entry.getKey(), context.serialize(entry.getValue()));
				return object;
			}

			@Override
			public ModpackFields deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
				if (json == null || !json.isJsonObject()) throw new JsonParseException("modpack must hold the pack name and its categories: modpack { name: \"\", <category> { <group> { ... } } }");
				ModpackFields fields = new ModpackFields();
				for (var entry : json.getAsJsonObject().entrySet()) {
					if (entry.getKey().equals("name")) {
						if (entry.getValue() == null || !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
							throw new JsonParseException("modpack.name must be a string; the name key is reserved and cannot be a category");
						fields.name = entry.getValue().getAsString();
					} else {
						fields.categories.put(entry.getKey(), context.deserialize(entry.getValue(), GROUPS_TYPE));
					}
				}
				return fields;
			}

			@Override
			public void collectUnknownKeys(JsonElement element, String prefix, List<String> unknown) {
				for (var entry : element.getAsJsonObject().entrySet()) {
					if (entry.getKey().equals("name")) continue;
					String categoryPath = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
					ConfigTools.collectUnknownKeys(entry.getValue(), GROUPS_TYPE, categoryPath, unknown);
				}
			}
		}
	}

	public static class GroupDeclaration {
		// UI metadata. The map key is the group id; displayName is what the player sees.
		public String displayName = "";
		public String description = "";

		// If required, the client cannot uncheck it. defaultSelected is ignored when required.
		public boolean required = false;
		public boolean defaultSelected = false;

		// Group ids this one conflicts with / depends on.
		public Set<String> breaksWith = Set.of();
		public Set<String> requires = Set.of();
		public Set<String> compatiblePlatforms = Set.of();

		/**
		 * File rules scoped to this group. Two postures: the group's directory under host-modpack is included in full
		 * and {@code excludedFiles} is the only way to leave content out of it, while nothing is synced from the server
		 * directory unless a {@code syncedFiles} rule includes it - excludedFiles carves exceptions out of either.
		 */
		public Set<String> syncedFiles = Set.of();
		public Set<String> excludedFiles = Set.of();
		public Set<String> allowEditsInFiles = Set.of();
	}
}
