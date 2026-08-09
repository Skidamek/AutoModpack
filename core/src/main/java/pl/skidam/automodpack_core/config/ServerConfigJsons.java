package pl.skidam.automodpack_core.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class ServerConfigJsons {

	public static class ServerConfigFieldsV1 {
		public int DO_NOT_CHANGE_IT = 1; // file version
		public String modpackName = "";
		public boolean modpackHost = true;
		public boolean generateModpackOnStart = true;
		public List<String> syncedFiles = List.of("/mods/*.jar", "/kubejs/**", "!/kubejs/server_scripts/**", "/emotes/*");
		public List<String> allowEditsInFiles = List.of("/options.txt", "/config/**");
		public boolean autoExcludeUnnecessaryFiles = true;
		public boolean requireAutoModpackOnClient = true;
		public boolean nagUnModdedClients = true;
		public String nagMessage = "This server provides dedicated modpack through AutoModpack!";
		public String nagClickableMessage = "Click here to get the AutoModpack!";
		public String nagClickableLink = "https://modrinth.com/project/automodpack";
		public boolean autoExcludeServerSideMods = true;
		public boolean hostModpackOnMinecraftPort = true;
		public String hostIp = "";
		public String hostLocalIp = "";
		public boolean updateIpsOnEveryStart = false;
		public int hostPort = -1;
		public boolean reverseProxy = false;
		public int bandwidthLimit = 0;
		public long secretLifetime = 336; // 336 hours = 14 days
		public boolean validateSecrets = true;
		public boolean selfUpdater = false;
		public List<String> acceptedLoaders;
	}

	public static class ServerConfigFieldsV2 {
		public int DO_NOT_CHANGE_IT = 2; // file version
		public String modpackName = "";
		public boolean modpackHost = true;
		public boolean generateModpackOnStart = true;
		public Set<String> syncedFiles = Set.of("/mods/*.jar", "/kubejs/**", "!/kubejs/server_scripts/**", "/emotes/*");
		public Set<String> allowEditsInFiles = Set.of("/options.txt", "/config/**");
		public Set<String> overwriteEditableFiles = Set.of();
		public boolean autoExcludeServerSideMods = true;
		public boolean autoExcludeUnnecessaryFiles = true;
		public boolean requireAutoModpackOnClient = true;
		public boolean nagUnModdedClients = true;
		public String nagMessage = "This server provides dedicated modpack through AutoModpack!";
		public String nagClickableMessage = "Click here to get the AutoModpack!";
		public String nagClickableLink = "https://modrinth.com/project/automodpack";
		public String bindAddress = "";
		public int bindPort = -1;
		@SerializedName(value = "advertisedEndpointHost", alternate = "addressToSend")
		public String advertisedEndpointHost = "";
		@SerializedName(value = "advertisedEndpointPort", alternate = "portToSend")
		public int advertisedEndpointPort = -1;
		public boolean disableInternalTLS = false;
		public ModpackConnectionMode connectionMode = ModpackConnectionMode.defaultFor(Constants.MC_VERSION, Constants.LOADER);
		public boolean updateIpsOnEveryStart = false;
		public int bandwidthLimit = 0;
		public boolean validateSecrets = true;
		public long secretLifetime = 336; // 336 hours = 14 days
		public boolean selfUpdater = false;
		public Set<String> acceptedLoaders = new HashSet<>();

	}

	public static class ServerConfigFieldsV3 {
		public int DO_NOT_CHANGE_IT = 3; // file version
		public String modpackName = "";
		public boolean modpackHost = true;
		public boolean generateModpackOnStart = true;
		// Replaces V2's flat syncedFiles/allowEditsInFiles/overwriteEditableFiles.
		// Key is the group id referenced by requires/breaksWith and by the client's saved selection.
		public Map<String, GroupDeclaration> groups = Map.of("main", mainGroupDeclaration());
		public boolean autoExcludeServerSideMods = true;
		public boolean autoExcludeUnnecessaryFiles = true;
		public boolean requireAutoModpackOnClient = true;
		public boolean nagUnModdedClients = true;
		public String nagMessage = "This server provides dedicated modpack through AutoModpack!";
		public String nagClickableMessage = "Click here to get the AutoModpack!";
		public String nagClickableLink = "https://modrinth.com/project/automodpack";
		public String bindAddress = "";
		public int bindPort = -1;
		@SerializedName(value = "advertisedEndpointHost", alternate = "addressToSend")
		public String advertisedEndpointHost = "";
		@SerializedName(value = "advertisedEndpointPort", alternate = "portToSend")
		public int advertisedEndpointPort = -1;
		public boolean disableInternalTLS = false;
		public ModpackConnectionMode connectionMode = ModpackConnectionMode.defaultFor(Constants.MC_VERSION, Constants.LOADER);
		public boolean updateIpsOnEveryStart = false;
		public int bandwidthLimit = 0;
		public boolean validateSecrets = true;
		public long secretLifetime = 336; // 336 hours = 14 days
		public boolean selfUpdater = false;
		public Set<String> acceptedLoaders = new HashSet<>();
	}

	// Default group for a fresh config and for migrating a V2 config, whose flat file
	// globs all belong to one implicitly-required group.
	public static GroupDeclaration mainGroupDeclaration() {
		GroupDeclaration declaration = new GroupDeclaration();
		declaration.displayName = "Main";
		declaration.description = "Core modpack files";
		declaration.required = true;
		declaration.defaultSelected = true;
		declaration.syncedFiles = Set.of("/mods/*.jar", "/kubejs/**", "!/kubejs/server_scripts/**", "/emotes/*");
		declaration.allowEditsInFiles = Set.of("/options.txt", "/config/**");
		return declaration;
	}

	public static class GroupDeclaration {
		// UI metadata. The map key is the group id; displayName is what the player sees.
		public String displayName = "";
		public String description = "";
		public String tag = "";

		// If required, the client cannot uncheck it. defaultSelected is ignored when required.
		public boolean required = false;
		public boolean defaultSelected = false;

		// Group ids this one conflicts with / depends on and its optional category tag.
		public Set<String> breaksWith = Set.of();
		public Set<String> requires = Set.of();
		public Set<String> compatiblePlatforms = Set.of();

		// Per-group equivalents of the V2 flat file rules.
		public Set<String> syncedFiles = Set.of();
		public Set<String> allowEditsInFiles = Set.of();
		public Set<String> overwriteEditableFiles = Set.of();
	}
}
