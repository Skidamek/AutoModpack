package pl.skidam.automodpack_core.config;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

public class ServerConfigJsons {

	public static class ServerConfigFieldsV3 {
		@HconfConfigs.Comment("file version - do not change")
		public int DO_NOT_CHANGE_IT = 3;
		@HconfConfigs.Comment("name shown to players joining the modpack")
		public String modpackName = "";
		@HconfConfigs.Comment("serve the modpack to clients from this server")
		public boolean modpackHost = true;
		@HconfConfigs.Comment("scan and regenerate the modpack on every server start")
		public boolean generateModpackOnStart = true;
		// Category name -> group id -> declaration. The group id is referenced by requires/breaksWith and by the client's saved selection; the category name is the player-facing section label.
		@HconfConfigs.Comment("modpack groups and their file rules; the braced form nests, rules are group-relative globs")
		public Map<String, Map<String, GroupDeclaration>> modpack = Map.of("General", Map.of("main", mainGroupDeclaration()));
		@HconfConfigs.Comment("leave mods marked as server-side out of the synced modpack")
		public boolean autoExcludeServerSideMods = true;
		@HconfConfigs.Comment("require clients to install the modpack")
		public boolean requireModpack = true;
		@HconfConfigs.Comment("show a message to players joining without the mod")
		public boolean nagUnModdedClients = true;
		@HconfConfigs.Comment("message shown to unmodded players")
		public String nagMessage = "Install the AutoModpack mod to get this server's modpack!";
		@HconfConfigs.Comment("text of the clickable nag message")
		public String nagClickableMessage = "Click here to get the AutoModpack!";
		@HconfConfigs.Comment("link the nag message opens")
		public String nagClickableLink = "https://modrinth.com/project/automodpack";
		@HconfConfigs.Comment("address the modpack host binds to; empty = all interfaces")
		public String bindAddress = "";
		@HconfConfigs.Comment("port the modpack host binds to; -1 = same as the server port")
		public int bindPort = -1;
		@HconfConfigs.Comment("host advertised to clients; empty = automatic")
		public String advertisedEndpointHost = "";
		@HconfConfigs.Comment("port advertised to clients; -1 = same as the bind port")
		public int advertisedEndpointPort = -1;
		@HconfConfigs.Comment("disable the built-in TLS listener")
		public boolean disableInternalTLS = false;
		@HconfConfigs.Comment("honor HAProxy PROXY protocol headers; enable only behind a trusted proxy")
		public boolean acceptProxyProtocol = false;
		@HconfConfigs.Comment("HOLEPUNCH, MAGIC or DIRECT")
		public ModpackConnectionMode connectionMode = ModpackConnectionMode.HOLEPUNCH;
		@HconfConfigs.Comment("per-client transfer cap in MiB/s; 0 = unlimited")
		public int bandwidthLimit = 0;
		@HconfConfigs.Comment("require clients to hold a server-issued secret")
		public boolean validateSecrets = true;
		@HconfConfigs.Comment("secret lifetime in hours; 336 = 14 days")
		public long secretLifetime = 336;
		@HconfConfigs.Comment("export the URL-contract tree (head, journal, objects/) to this directory after every publish for any static HTTPS host; empty = off")
		public String exportHttpDirectory = "";
		@HconfConfigs.Comment("include every object in the HTTP export even when Modrinth or CurseForge serves it; keeps the host as a backstop for link rot")
		public boolean exportHttpIncludeAll = false;
		@HconfConfigs.Comment("let the mod update itself")
		public boolean selfUpdater = false;
		@HconfConfigs.Comment("loaders a client may run the modpack with; seeded once, then yours to edit")
		public Set<String> acceptedLoaders = new HashSet<>();
		@HconfConfigs.Comment("advertise the pack's loader and Minecraft versions so clients switch their instance to match; off = files-only pack")
		public boolean advertiseVersionsToSync = true;
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

	// Default group for a fresh config. The sets are linked so a generated file is byte-identical across runs (§7.4 determinism).
	private static GroupDeclaration mainGroupDeclaration() {
		GroupDeclaration declaration = new GroupDeclaration();
		declaration.displayName = "Main";
		declaration.description = "Core modpack files";
		declaration.required = true;
		declaration.defaultSelected = true;
		declaration.syncedFiles = new LinkedHashSet<>(List.of("mods/*.jar", "kubejs/**", "emotes/*"));
		declaration.excludedFiles = new LinkedHashSet<>(List.of(".*", ".*/**", "**/.*", "**/.*/**", "*.tmp", "**/*.tmp", "*.disabled", "**/*.disabled", "*.bak", "**/*.bak", "kubejs/server_scripts/**"));
		declaration.allowEditsInFiles = new LinkedHashSet<>(List.of("options.txt", "config/**"));
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
