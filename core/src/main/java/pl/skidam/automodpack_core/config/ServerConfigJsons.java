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
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class ServerConfigJsons {

	public static class ServerConfigFieldsV3 {
		@SerializedName("modpack-host")
		public boolean modpackHost = true;
		@SerializedName("generate-modpack-on-start")
		public boolean generateModpackOnStart = true;
		@ReconfConfigs.Comment("host-modpack/<id>/ is included in full. from-server pulls extra files from the server root. exclude and editable apply after that; they do not add files.")
		public ModpackFields modpack = ModpackFields.withMainGroup();
		@SerializedName("auto-exclude-server-side-mods")
		public boolean autoExcludeServerSideMods = true;
		@SerializedName("require-modpack")
		public boolean requireModpack = true;
		@SerializedName("nag-un-modded-clients")
		public boolean nagUnModdedClients = true;
		@SerializedName("nag-message")
		public String nagMessage = "Install the AutoModpack mod to get this server's modpack!";
		@SerializedName("nag-clickable-message")
		public String nagClickableMessage = "Click here to get the AutoModpack!";
		@SerializedName("nag-clickable-link")
		public String nagClickableLink = "https://modrinth.com/project/automodpack";
		@SerializedName("bind-address")
		public String bindAddress = "";
		@SerializedName("bind-port")
		public int bindPort = -1;
		@SerializedName("advertised-endpoint-host")
		public String advertisedEndpointHost = "";
		@SerializedName("advertised-endpoint-port")
		public int advertisedEndpointPort = -1;
		@SerializedName("disable-internal-tls")
		public boolean disableInternalTLS = false;
		@SerializedName("accept-proxy-protocol")
		public boolean acceptProxyProtocol = false;
		@ReconfConfigs.Comment("HOLEPUNCH uses the Minecraft port. MAGIC can share it or use bindPort. HTTP uses bindPort.")
		@SerializedName("connection-mode")
		public ModpackConnectionMode connectionMode = ModpackConnectionMode.HOLEPUNCH;
		@SerializedName("bandwidth-limit")
		public int bandwidthLimit = 0;
		@ReconfConfigs.Comment("require a login-issued download secret")
		@SerializedName("validate-secrets")
		public boolean validateSecrets = true;
		@SerializedName("secret-lifetime")
		public long secretLifetime = 336;
		@SerializedName("export-http-directory")
		public String exportHttpDirectory = "";
		@SerializedName("export-http-include-all")
		public boolean exportHttpIncludeAll = false;
		@SerializedName("self-updater")
		public boolean selfUpdater = false;
		@ReconfConfigs.Comment("extra loaders allowed to join; this server's loader is always accepted")
		@SerializedName("accepted-loaders")
		public Set<String> acceptedLoaders = new HashSet<>();
		@ReconfConfigs.Comment("tell clients the pack Minecraft and loader versions")
		@SerializedName("advertise-versions-to-sync")
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

	static final List<String> FACTORY_FROM_SERVER = List.of("mods/*.jar", "kubejs/**", "emotes/*");
	static final List<String> FACTORY_JUNK_EXCLUDE = List.of("**/.*", "**/.*/**", "**/*.{tmp,disabled,bak}");
	static final List<String> FACTORY_EXCLUDE = List.of("**/.*", "**/.*/**", "**/*.{tmp,disabled,bak}", "kubejs/server_scripts/**");
	static final List<String> FACTORY_EDITABLE = List.of("options.txt", "config/**");

	private static GroupDeclaration mainGroupDeclaration() {
		GroupDeclaration declaration = new GroupDeclaration();
		declaration.description = "Core modpack files";
		declaration.required = true;
		declaration.defaultSelected = true;
		declaration.fromServer = new LinkedHashSet<>(FACTORY_FROM_SERVER);
		declaration.exclude = new LinkedHashSet<>(FACTORY_EXCLUDE);
		declaration.editable = new LinkedHashSet<>(FACTORY_EDITABLE);
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
			fields.categories.get("General").get("main").fromServer = Set.of();
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
		@SerializedName("display-name")
		public String displayName = "";
		public String description = "";

		public boolean required = false;
		@SerializedName("default-selected")
		public boolean defaultSelected = false;

		@SerializedName("breaks-with")
		public Set<String> breaksWith = Set.of();
		public Set<String> requires = Set.of();
		@SerializedName("compatible-platforms")
		public Set<String> compatiblePlatforms = Set.of();

		/**
		 * File rules scoped to this group. Two postures: the group's directory under host-modpack is included in full
		 * and {@code exclude} is the only way to leave content out of it, while nothing is synced from the server
		 * directory unless a {@code from-server} rule includes it - exclude carves exceptions out of either.
		 */
		@ReconfConfigs.Comment("extra paths from the server root")
		@SerializedName("from-server")
		public Set<String> fromServer = Set.of();
		@ReconfConfigs.Comment("drop these from the pack; they do not add files")
		public Set<String> exclude = Set.of();
		@ReconfConfigs.Comment("pack files players may change; listing a path here does not add it to the pack")
		public Set<String> editable = Set.of();
	}
}
