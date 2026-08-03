
package pl.skidam.automodpack_core.config;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;

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

		public ClientConfigFieldsV3() {}

		public ClientConfigFieldsV3(ClientConfigFieldsV3 source) {
			this.selectedModpackId = source.selectedModpackId;
			this.modpackConnections = source.modpackConnections == null ? new HashMap<>() : new HashMap<>(source.modpackConnections);
			this.updateSelectedModpackOnLaunch = source.updateSelectedModpackOnLaunch;
			this.selfUpdater = source.selfUpdater;
			this.syncAutoModpackVersion = source.syncAutoModpackVersion;
			this.syncLoaderVersion = source.syncLoaderVersion;
			this.playMusic = source.playMusic;
		}
	}

	public static class ConnectionInfo {
		@SerializedName(value = "origin", alternate = "serverAddress")
		public InetSocketAddress origin; // player-entered Minecraft identity and certificate trust root
		@SerializedName(value = "endpoint", alternate = "hostAddress")
		public InetSocketAddress endpoint; // server-advertised AutoModpack route; not an authenticated identity
		public ModpackConnectionMode connectionMode;
		public transient String expectedFingerprint; // runtime-only exact certificate pin bound to origin
		public transient String trustReason; // non-null only while importing new trust

		public ConnectionInfo() {}

		public ConnectionInfo(InetSocketAddress origin, InetSocketAddress endpoint, ModpackConnectionMode connectionMode, String expectedFingerprint, String trustReason) {
			this.origin = origin;
			this.endpoint = endpoint;
			this.connectionMode = connectionMode;
			this.expectedFingerprint = expectedFingerprint;
			this.trustReason = trustReason;
		}

		public boolean isComplete() {
			return origin != null && endpoint != null && connectionMode != null && !origin.getHostString().isBlank() && !endpoint.getHostString().isBlank();
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
		// Replaces V2's flat syncedFiles/allowEditsInFiles/overwriteEditableFiles/forceCopyFilesToStandardLocation.
		// Key is the group id referenced by requires/breaksWith and by the client's saved selection.
		public Map<String, GroupDeclaration> groups = Map.of("main", mainGroupDeclaration());
		public Map<String, SelectionTagDeclaration> selectionTags = Map.of();
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
		declaration.recommended = true;
		declaration.syncedFiles = Set.of("/mods/*.jar", "/kubejs/**", "!/kubejs/server_scripts/**", "/emotes/*");
		declaration.allowEditsInFiles = Set.of("/options.txt", "/config/**");
		return declaration;
	}

	public static class GroupDeclaration {
		// UI metadata. The map key is the group id; displayName is what the player sees.
		public String displayName = "";
		public String description = "";
		public String tag = "";

		// If required, the client cannot uncheck it. recommended is ignored when required.
		public boolean required = false;
		public boolean recommended = false;

		// Group ids this one conflicts with / depends on and its optional selection tag.
		public Set<String> breaksWith = Set.of();
		public Set<String> requires = Set.of();
		public Set<String> compatiblePlatforms = Set.of();

		// Per-group equivalents of the V2 flat file rules.
		public Set<String> syncedFiles = Set.of();
		public Set<String> allowEditsInFiles = Set.of();
		public Set<String> overwriteEditableFiles = Set.of();
		public Set<String> forceCopyFilesToStandardLocation = Set.of();
	}

	public static class SelectionTagDeclaration {
		public String displayName = "";
		public String description = "";
		public boolean defaultSelected = false;
		public boolean serverForced = false;
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
		public ModpackConnectionMode connectionMode;
	}

	public static class GenerationPointerFields {
		public int schemaVersion;
		public String generationId = "";
	}

	public static class OwnershipLedgerFields {
		public String modpackId = "";
		public List<EntryFields> entries = List.of();
		public String digest = "";

		public static class EntryFields {
			public String logicalPath = "";
			public List<ContentFields> historicalHashes = List.of();
			public Set<String> historicalGroupIds = Set.of();
			public String firstPublishedGenerationId = "";
			public String lastPublishedGenerationId = "";
			public String currentStatus = "";
		}

		public static class ContentFields {
			public String sha1 = "";
			public long size;

			public ContentFields() {}

			public ContentFields(String sha1, long size) {
				this.sha1 = sha1;
				this.size = size;
			}
		}
	}

	public static class ClientBaselineFields {
		public int schemaVersion = 1;
		public String modpackId = "";
		public List<EntryFields> entries = List.of();

		public static class EntryFields {
			public String logicalPath = "";
			public String objectHash = "";
			public long size = -1;
			public boolean absent;
			public String baselineGenerationId = "";
		}
	}

	public static class ClientRecoveryArchiveFields {
		public int schemaVersion = 1;
		public List<EntryFields> entries = List.of();

		public static class EntryFields {
			public String logicalPath = "";
			public String sha1 = "";
			public long size = -1;
			public String sourceGenerationId = "";
			public String preservedAt = "";
		}
	}

	public static class OwnershipDeltaFields {
		public String modpackId = "";
		public List<ChangeFields> changes = List.of();
		public String digest = "";

		public static class ChangeFields {
			public String logicalPath = "";
			public String kind = "";
			public OwnershipLedgerFields.ContentFields content;
			public List<OwnershipLedgerFields.ContentFields> contents = List.of();
			public Set<String> groupIds = Set.of();
		}
	}

	public static class ClientContentHistoryFields {
		public int schemaVersion = 1;
		public String modpackId = "";
		public List<EntryFields> entries = List.of();

		public static class EntryFields {
			public String patchNotes = "";
			public String stateDigest = "";
			public String modpackName = "";
			public String recordedAt = "";
			public Set<String> selectedTags = Set.of();
			public Set<String> selectedGroups = Set.of();
			public String fileSummary = "";
		}
	}

	public static class CompleteModpackContentFields {
		public String modpackId = "";
		public String modpackName = "";
		public String automodpackVersion = "";
		public String loader = "";
		public String loaderVersion = "";
		public String mcVersion = "";
		public Map<String, ModpackGroupFields> groups = Map.of();
		public Map<String, SelectionTagFields> selectionTags = Map.of();
		public OwnershipLedgerFields ownershipLedger = new OwnershipLedgerFields();
		public GenerationFields generation;

		public static class GenerationFields {
			public int schemaVersion;
			public String generationId = "";
			public String parentGenerationId = "";
			public String createdAt = "";
			public String stateDigest = "";
			public String ledgerDigest = "";
			public String patchNotes = "";
			public String patchNotesDigest = "";
			public String rollbackTargetGenerationId = "";
		}

		public static class ModpackGroupFields {
			public String displayName = "";
			public String description = "";
			public String tag = "";
			public boolean required;
			public boolean recommended;
			public Set<String> breaksWith = Set.of();
			public Set<String> requires = Set.of();
			public Set<String> compatiblePlatforms = Set.of();
			public Map<String, GroupFileFields> files = Map.of();
		}

		public static class SelectionTagFields {
			public String displayName = "";
			public String description = "";
			public boolean defaultSelected;
			public boolean serverForced;
		}

		public static class GroupFileFields {
			public String size = "";
			public String type = "";
			public boolean editable;
			public boolean overwriteEditable;
			public boolean forceCopy;
			public String sha1 = "";
			public String murmur;

			public GroupFileFields() {}

			public GroupFileFields(String size, String type, boolean editable, boolean overwriteEditable, boolean forceCopy, String sha1, String murmur) {
				this.size = size;
				this.type = type;
				this.editable = editable;
				this.overwriteEditable = overwriteEditable;
				this.forceCopy = forceCopy;
				this.sha1 = sha1;
				this.murmur = murmur;
			}
		}
	}

	public static class ModpackContentFields {
		public String modpackId = "";
		public String modpackName = "";
		public String automodpackVersion = "";
		public String loader = "";
		public String loaderVersion = "";
		public String mcVersion = "";
		public Set<ModpackContentItem> list;
		public Set<String> selectedGroups = Set.of();
		public OwnershipLedgerFields ownershipLedger = new OwnershipLedgerFields();
		public String targetGenerationId = "";
		public String parentGenerationId = "";
		public String stateDigest = "";

		public ModpackContentFields(Set<ModpackContentItem> list) {
			this.list = list;
		}

		public ModpackContentFields() {
			this.list = Set.of();
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

			@Override
			public boolean equals(Object obj) {
				if (this == obj) return true;
				if (obj == null || getClass() != obj.getClass()) return false;
				ModpackContentItem that = (ModpackContentItem) obj;
				return editable == that.editable && overwriteEditable == that.overwriteEditable && forceCopy == that.forceCopy
						&& Objects.equals(file, that.file) && Objects.equals(size, that.size) && Objects.equals(type, that.type)
						&& Objects.equals(sha1, that.sha1) && Objects.equals(murmur, that.murmur);
			}

			@Override
			public int hashCode() {
				return Objects.hash(file, size, type, editable, overwriteEditable, forceCopy, sha1, murmur);
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

	// Per-modpack record of which groups the player picked, so the selection screen is shown
	// once and later launches reuse the answer. Keyed by modpack id, matching
	// ClientConfigFieldsV3.modpackConnections, which already owns the connection details.
	public static class ClientSelectionStoreFields {
		public int DO_NOT_CHANGE_IT = 1; // file version
		public Map<String, ModpackSelection> selections = new HashMap<>();

		public static class ModpackSelection {
			public Set<String> requestedTags = new HashSet<>();
			@SerializedName(value = "requestedGroups", alternate = "selectedGroups")
			public Set<String> requestedGroups = new HashSet<>();
			public Set<String> excludedGroups = new HashSet<>();

			public ModpackSelection() {}

			public ModpackSelection(Set<String> requestedGroups) {
				this(Set.of(), requestedGroups, Set.of());
			}

			public ModpackSelection(Set<String> requestedTags, Set<String> requestedGroups, Set<String> excludedGroups) {
				this.requestedTags = requestedTags;
				this.requestedGroups = requestedGroups;
				this.excludedGroups = excludedGroups;
			}
		}
	}
}
