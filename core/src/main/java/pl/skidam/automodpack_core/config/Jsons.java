
package pl.skidam.automodpack_core.config;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.auth.Secrets;

@SuppressWarnings("unused")
public class Jsons {

	public static class VersionConfigField {
		public int DO_NOT_CHANGE_IT = 0; // file version
	}

	public static class ClientConfigFieldsV3 {
		public int DO_NOT_CHANGE_IT = 3; // file version
		public String selectedModpackId = "";
		@SerializedName(value = "modpackConnections", alternate = "installedModpacks")
		public Map<String, ConnectionInfo> modpackConnections = new HashMap<>(); // modpack ID, known origin and endpoint routing
		public boolean updateSelectedModpackOnLaunch = true;
		public boolean selfUpdater = false;
		public boolean syncAutoModpackVersion = true;
		public boolean syncLoaderVersion = true;
		public boolean playMusic = true;
		public boolean allowRemoteNonModpackDeletions = true;

		public ClientConfigFieldsV3() {}

		public ClientConfigFieldsV3(ClientConfigFieldsV3 source) {
			this.selectedModpackId = source.selectedModpackId;
			this.modpackConnections = source.modpackConnections == null ? new HashMap<>() : new HashMap<>(source.modpackConnections);
			this.updateSelectedModpackOnLaunch = source.updateSelectedModpackOnLaunch;
			this.selfUpdater = source.selfUpdater;
			this.syncAutoModpackVersion = source.syncAutoModpackVersion;
			this.syncLoaderVersion = source.syncLoaderVersion;
			this.playMusic = source.playMusic;
			this.allowRemoteNonModpackDeletions = source.allowRemoteNonModpackDeletions;
		}
	}

	public static class ConnectionInfo {
		@SerializedName(value = "origin", alternate = "serverAddress")
		public InetSocketAddress origin; // player-entered Minecraft identity and certificate trust root
		@SerializedName(value = "endpoint", alternate = "hostAddress")
		public InetSocketAddress endpoint; // server-advertised AutoModpack route; not an authenticated identity
		public boolean requiresMagic;
		public transient String expectedFingerprint; // runtime-only exact certificate pin bound to origin
		public transient String trustReason; // non-null only while importing new trust

		public ConnectionInfo() {}

		public ConnectionInfo(InetSocketAddress origin, InetSocketAddress endpoint, boolean requiresMagic, String expectedFingerprint, String trustReason) {
			this.origin = origin;
			this.endpoint = endpoint;
			this.requiresMagic = requiresMagic;
			this.expectedFingerprint = expectedFingerprint;
			this.trustReason = trustReason;
		}

		public boolean isComplete() {
			return origin != null && endpoint != null && !origin.getHostString().isBlank() && !endpoint.getHostString().isBlank();
		}
	}

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
		public Set<String> forceCopyFilesToStandardLocation = Set.of();
		public Map<String, String> nonModpackFilesToDelete = Map.of();
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
		public boolean requireMagicPackets = false;
		public boolean updateIpsOnEveryStart = false;
		public int bandwidthLimit = 0;
		public boolean validateSecrets = true;
		public long secretLifetime = 336; // 336 hours = 14 days
		public boolean selfUpdater = false;
		public Set<String> acceptedLoaders = new HashSet<>();

		public static class FileToDelete { // Same as in ModpackContentFields.FileToDelete but without timestamp
			public final String file;
			public final String sha1;

			public FileToDelete(String file, String sha1) {
				this.file = file;
				this.sha1 = sha1;
			}
		}
	}

	public static class ServerConfigFieldsV3 {
		public int DO_NOT_CHANGE_IT = 3; // file version
		public String modpackName = "";
		public boolean modpackHost = true;
		public boolean generateModpackOnStart = true;
		// Replaces V2's flat syncedFiles/allowEditsInFiles/overwriteEditableFiles/forceCopyFilesToStandardLocation.
		// Key is the group id referenced by requires/breaksWith and by the client's saved selection.
		public Map<String, GroupDeclaration> groups = Map.of("main", mainGroupDeclaration());
		public Map<String, String> nonModpackFilesToDelete = Map.of();
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
		public boolean requireMagicPackets = false;
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
		declaration.recommended = true;
		declaration.syncedFiles = Set.of("/mods/*.jar", "/kubejs/**", "!/kubejs/server_scripts/**", "/emotes/*");
		declaration.allowEditsInFiles = Set.of("/options.txt", "/config/**");
		return declaration;
	}

	public static class GroupDeclaration {
		// UI metadata. The map key is the group id; displayName is what the player sees.
		public String displayName = "";
		public String description = "";
		public String category = "";

		// If required, the client cannot uncheck it. recommended is ignored when required.
		public boolean required = false;
		public boolean recommended = false;

		// Group ids this one conflicts with / depends on.
		public Set<String> breaksWith = Set.of();
		public Set<String> requires = Set.of();

		// WINDOWS, MACOS, LINUX, ANDROID; prefix with ! to negate. Empty = every OS.
		public Set<String> compatibleOS = Set.of();

		// Per-group equivalents of the V2 flat file rules.
		public Set<String> syncedFiles = Set.of();
		public Set<String> allowEditsInFiles = Set.of();
		public Set<String> overwriteEditableFiles = Set.of();
		public Set<String> forceCopyFilesToStandardLocation = Set.of();
	}

	public static class ServerCoreConfigFields {
		public String automodpackVersion = "";
		public String loader = "";
		public String loaderVersion = "";
		public String mcVersion = "";
	}

	public static class SecretsFields {
		public Map<String, Secrets.Secret> secrets = new HashMap<>();
	}

	public static class KnownHostsFields {
		public Map<String, CertificateTrustEntry> hosts; // canonical Minecraft origin, exact certificate trust
	}

	public static class CertificateTrustEntry {
		public String fingerprint;
		public String reason;

		public CertificateTrustEntry() {}

		public CertificateTrustEntry(String fingerprint, String reason) {
			this.fingerprint = fingerprint;
			this.reason = reason;
		}
	}

	public static class KnownHostsBootstrapFields {
		public String origin;
		public String fingerprint;
		public String modpackId;
		public String endpoint;
		public Boolean requiresMagic;
	}

	public static class ModpackContentFields {
		public String modpackId = "";
		public String modpackName = "";
		public String automodpackVersion = "";
		public String loader = "";
		public String loaderVersion = "";
		public String mcVersion = "";
		public Set<ModpackContentItem> list;
		public Set<FileToDelete> nonModpackFilesToDelete = Set.of();
		// Group id -> metadata. Each group's files are referenced by path into `list`, which stays the
		// canonical flat file set so clients that ignore groups still see a complete modpack.
		public Map<String, ModpackGroupFields> groups = Map.of();

		public ModpackContentFields(Set<ModpackContentItem> list) {
			this.list = list;
		}

		public ModpackContentFields() {
			this.list = Set.of();
		}

		public static class ModpackGroupFields {
			// Mirrors Jsons.GroupDeclaration, minus the server-only file globs.
			public String displayName = "";
			public String description = "";
			public String category = "";
			public boolean required;
			public boolean recommended;
			public Set<String> breaksWith = Set.of();
			public Set<String> requires = Set.of();
			public Set<String> compatibleOS = Set.of();

			// Populated by the scanner: relative paths of the ModpackContentItems in this group.
			public Set<String> files = new HashSet<>();

			public ModpackGroupFields() {}

			public ModpackGroupFields(GroupDeclaration declaration) {
				this.displayName = declaration.displayName;
				this.description = declaration.description;
				this.category = declaration.category;
				this.required = declaration.required;
				this.recommended = declaration.recommended;
				this.breaksWith = declaration.breaksWith;
				this.requires = declaration.requires;
				this.compatibleOS = declaration.compatibleOS;
			}
		}

		public static class ModpackContentItem {
			public final String file;
			public final String size;
			public final String type;
			public final boolean editable;
			public final boolean overwriteEditable;
			public final boolean forceCopy;
			public final String sha1;
			public final String murmur;

			public ModpackContentItem(String file, String size, String type, boolean editable, boolean overwriteEditable, boolean forceCopy, String sha1, String murmur) {
				this.file = file;
				this.size = size;
				this.type = type;
				this.editable = editable;
				this.overwriteEditable = overwriteEditable;
				this.forceCopy = forceCopy;
				this.sha1 = sha1;
				this.murmur = murmur;
			}

			@Override
			public String toString() {
				return String.format("ModpackContentItems(file=%s, size=%s, type=%s, editable=%s, forceCopy=%s, sha1=%s, murmur=%s)", file, size, type,
						editable, forceCopy, sha1, murmur);
			}

			// if the relative file path is the same, we consider the items equal
			@Override
			public boolean equals(Object obj) {
				if (this == obj) return true;
				if (obj == null || getClass() != obj.getClass()) return false;
				ModpackContentItem that = (ModpackContentItem) obj;
				return Objects.equals(file, that.file);
			}

			@Override
			public int hashCode() {
				return Objects.hash(file);
			}
		}

		public static class FileToDelete {
			public final String file;
			public final String sha1;
			public final String timestamp;

			public FileToDelete(String file, String sha1, String timestamp) {
				this.file = file;
				this.sha1 = sha1;
				this.timestamp = timestamp;
			}
		}
	}

	// seems kinda too verbose and it may take too much space for large modpack but lets keep it for now
	public static class LocalMetadata {
		// Map of File Path -> Fingerprint
		public Map<String, FileFingerprint> files = new ConcurrentHashMap<>();

		public static class FileFingerprint {
			public final String sha1;
			public final long lastSize; // Local disk size
			public final long lastModified; // Local disk timestamp

			public FileFingerprint(String sha1, long lastSize, long lastModified) {
				this.sha1 = sha1;
				this.lastSize = lastSize;
				this.lastModified = lastModified;
			}
		}
	}

	public static class ClientDummyFiles {
		// Set of absolute file paths to delete when we can
		public Set<String> files = ConcurrentHashMap.newKeySet();
	}

	public static class ClientDeletedNonModpackFilesTimestamps {
		// Set of timestamps of the files to delete
		public Set<String> timestamps = ConcurrentHashMap.newKeySet();
	}

	// Per-modpack record of which groups the player picked, so the selection screen is shown
	// once and later launches reuse the answer. Keyed by modpack id, matching
	// ClientConfigFieldsV3.modpackConnections, which already owns the connection details.
	public static class ClientSelectionManagerFields {
		public int DO_NOT_CHANGE_IT = 1; // file version
		public Map<String, ModpackSelection> selections = new HashMap<>();

		public static class ModpackSelection {
			public Set<String> selectedGroups = new HashSet<>();

			public ModpackSelection() {}

			public ModpackSelection(Set<String> selectedGroups) {
				this.selectedGroups = selectedGroups;
			}
		}
	}
}
