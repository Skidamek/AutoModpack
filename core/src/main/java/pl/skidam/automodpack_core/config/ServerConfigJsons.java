package pl.skidam.automodpack_core.config;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.modpack.group.CategoryPickMode;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class ServerConfigJsons {

	public static class ServerConfigFieldsV3 {
		@SerializedName("modpack-host")
		public boolean modpackHost = true;
		@SerializedName("generate-modpack-on-start")
		public boolean generateModpackOnStart = true;
		public ModpackFields modpack = ModpackFields.withMainGroup();
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
		@SerializedName("connection-mode")
		public ModpackConnectionMode connectionMode = ModpackConnectionMode.HOLEPUNCH;
		@SerializedName("bandwidth-limit")
		public int bandwidthLimit = 0;
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
		@SerializedName("accepted-loaders")
		public Set<String> acceptedLoaders = new HashSet<>();
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
		declaration.selection = GroupSelectionMode.REQUIRED;
		declaration.fromServer = new LinkedHashSet<>(FACTORY_FROM_SERVER);
		return declaration;
	}

	public static class ModpackFields {
		public String name = "";
		@SerializedName("auto-exclude-server-side-mods")
		public ServerSideModExclusion autoExcludeServerSideMods = ServerSideModExclusion.METADATA;
		public Set<String> exclude = Set.of();
		public Set<String> editable = Set.of();
		public Map<String, CategoryDeclaration> categories = new LinkedHashMap<>();
		public Map<String, GroupDeclaration> groups = new LinkedHashMap<>();

		public static ModpackFields withMainGroup() {
			ModpackFields fields = new ModpackFields();
			fields.exclude = new LinkedHashSet<>(FACTORY_EXCLUDE);
			fields.editable = new LinkedHashSet<>(FACTORY_EDITABLE);
			fields.groups = new LinkedHashMap<>(Map.of("main", mainGroupDeclaration()));
			return fields;
		}

		public static ModpackFields withStandaloneMain() {
			ModpackFields fields = withMainGroup();
			fields.groups.get("main").fromServer = Set.of();
			return fields;
		}
	}

	public static class CategoryDeclaration {
		public String name = "";
		public String description = "";
		public CategoryPickMode pick = CategoryPickMode.ANY;
	}

	public static class GroupDeclaration {
		public String name = "";
		public String description = "";
		public String category = "";
		public GroupSelectionMode selection = GroupSelectionMode.OPTIONAL;
		@SerializedName("auto-exclude-server-side-mods")
		public ServerSideModExclusion autoExcludeServerSideMods;
		public Set<String> requires = Set.of();
		public Set<String> conflicts = Set.of();
		@SerializedName("compatible-platforms")
		public Set<String> compatiblePlatforms = Set.of();
		@SerializedName("from-server")
		public Set<String> fromServer = Set.of();
		public Set<String> exclude = Set.of();
		public Set<String> editable = Set.of();
	}
}
