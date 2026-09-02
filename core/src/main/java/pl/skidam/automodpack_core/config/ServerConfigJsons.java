package pl.skidam.automodpack_core.config;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class ServerConfigJsons {

	public static class ServerConfigFieldsV3 {
		public int DO_NOT_CHANGE_IT = 3; // file version
		public String modpackName = "";
		public boolean modpackHost = true;
		public boolean generateModpackOnStart = true;
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
		public String advertisedEndpointHost = "";
		public int advertisedEndpointPort = -1;
		public boolean disableInternalTLS = false;
		public ModpackConnectionMode connectionMode = ModpackConnectionMode.HOLEPUNCH;
		public int bandwidthLimit = 0;
		public boolean validateSecrets = true;
		public long secretLifetime = 336; // 336 hours = 14 days
		public boolean selfUpdater = false;
		public Set<String> acceptedLoaders = new HashSet<>();
		public boolean syncLoaderVersion = true;
	}

	// Default group for a fresh config.
	private static GroupDeclaration mainGroupDeclaration() {
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
		public String category = "";

		// If required, the client cannot uncheck it. defaultSelected is ignored when required.
		public boolean required = false;
		public boolean defaultSelected = false;

		// Group ids this one conflicts with / depends on and its optional player-facing category.
		public Set<String> breaksWith = Set.of();
		public Set<String> requires = Set.of();
		public Set<String> compatiblePlatforms = Set.of();

		// File rules scoped to this group.
		public Set<String> syncedFiles = Set.of();
		public Set<String> allowEditsInFiles = Set.of();
	}
}
