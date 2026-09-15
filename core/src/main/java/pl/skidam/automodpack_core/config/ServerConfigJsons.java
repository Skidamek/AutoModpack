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
		// Category name -> group id -> declaration. The group id is referenced by requires/breaksWith and by the client's saved selection; the category name is the player-facing section label.
		public Map<String, Map<String, GroupDeclaration>> modpack = Map.of("General", Map.of("main", mainGroupDeclaration()));
		public boolean autoExcludeServerSideMods = true;
		public boolean requireModpack = true;
		public boolean nagUnModdedClients = true;
		public String nagMessage = "Install the AutoModpack mod to get this server's modpack!";
		public String nagClickableMessage = "Click here to get the AutoModpack!";
		public String nagClickableLink = "https://modrinth.com/project/automodpack";
		public String bindAddress = "";
		public int bindPort = -1;
		public String advertisedEndpointHost = "";
		public int advertisedEndpointPort = -1;
		public boolean disableInternalTLS = false;
		/** Honor HAProxy PROXY protocol headers on dedicated listeners; enable only when a trusted proxy fronts AutoModpack, since a claimed source address feeds IP bans and audit logs. */
		public boolean acceptProxyProtocol = false;
		public ModpackConnectionMode connectionMode = ModpackConnectionMode.HOLEPUNCH;
		/** Cap on per-client transfer speed in MiB/s; 0 disables limiting. */
		public int bandwidthLimit = 0;
		public boolean validateSecrets = true;
		public long secretLifetime = 336; // 336 hours = 14 days
		/** Non-empty: the URL-contract tree (head, journal, objects/) is exported to this directory after every publish, ready to serve with any static HTTPS host. */
		public String exportHttpDirectory = "";
		public boolean selfUpdater = false;
		/** Loaders a client may run the modpack with; seeded with this server's loader on first load only, never re-added after the admin edits the set. */
		public Set<String> acceptedLoaders = new HashSet<>();
		public boolean syncLoaderVersion = true;
	}

	/**
	 * Defaults for a fresh standalone host config: no game handshake exists to provision secrets, and nothing is synced from the host directory unless the admin adds rules. Values land in the file once and are the
	 * admin's to change.
	 */
	public static ServerConfigFieldsV3 standalone() {
		ServerConfigFieldsV3 config = new ServerConfigFieldsV3();
		config.validateSecrets = false;
		GroupDeclaration main = mainGroupDeclaration();
		main.syncedFiles = Set.of();
		config.modpack = Map.of("General", Map.of("main", main));
		return config;
	}

	// Default group for a fresh config.
	private static GroupDeclaration mainGroupDeclaration() {
		GroupDeclaration declaration = new GroupDeclaration();
		declaration.displayName = "Main";
		declaration.description = "Core modpack files";
		declaration.required = true;
		declaration.defaultSelected = true;
		declaration.syncedFiles = Set.of("mods/*.jar", "kubejs/**", "emotes/*");
		declaration.excludedFiles = Set.of(".*", ".*/**", "**/.*", "**/.*/**", "*.tmp", "**/*.tmp", "*.disabled", "**/*.disabled", "*.bak", "**/*.bak", "kubejs/server_scripts/**");
		declaration.allowEditsInFiles = Set.of("options.txt", "config/**");
		return declaration;
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
