
package pl.skidam.automodpack_core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import pl.skidam.automodpack_core.auth.TrustEvidence;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused")
public class Jsons {

    public static class VersionConfigField {
        public int DO_NOT_CHANGE_IT = 0; // file version
    }

    public static class ClientConfigFieldsV1 {
        public int DO_NOT_CHANGE_IT = 1; // file version
        public String selectedModpack = ""; // modpack name
        public Map<String, String> installedModpacks; // modpack name, <modpack host address, minecraft server address>
        public boolean selfUpdater = false;
    }

    public static class ClientConfigFieldsV2 {
        public int DO_NOT_CHANGE_IT = 2; // file version
        public String selectedModpack = ""; // modpack name
        public Map<String, ModpackAddresses> installedModpacks; // modpack name, <modpack host address, minecraft server address>
        public boolean updateSelectedModpackOnLaunch = true;
        public boolean selfUpdater = false;
        public boolean syncAutoModpackVersion = true;
        public boolean syncLoaderVersion = true;
        public boolean playMusic = true;
        public boolean allowRemoteNonModpackDeletions = true;
    }

    public static class ClientConfigFieldsV3 {
        public int DO_NOT_CHANGE_IT = 3; // file version
        public String selectedModpack = ""; // modpack name
        public Map<String, PersistedModpackConnection> installedModpacks; // modpack name -> persisted connection details
        public boolean updateSelectedModpackOnLaunch = true;
        public boolean selfUpdater = false;
        public boolean syncAutoModpackVersion = true;
        public boolean syncLoaderVersion = true;
        public boolean playMusic = true;
        public boolean allowRemoteNonModpackDeletions = true;
    }

    @Deprecated
    public static class ModpackAddresses {
        public String endpointId; // iroh endpoint id
        public transient List<InetSocketAddress> directAddresses; // legacy migration-only field
        public InetSocketAddress hostAddress; // modpack host address
        public InetSocketAddress serverAddress; // minecraft server address
        public boolean shareMinecraftConnection; // if true, client may carry iroh packets inside the login connection
        public transient boolean requiresMagic; // legacy migration-only field

        public ModpackAddresses() {
            // Default constructor for Gson
        }

        /**
         * ModpackEntry holds server connection details.
         *
         * @param hostAddress   modpack server address that COULD be manipulated by the server
         * @param serverAddress minecraft server address that represents the target address
         *                      which client uses to connect. This value CANNOT be manipulated by the server.
         */
        public ModpackAddresses(InetSocketAddress hostAddress, InetSocketAddress serverAddress) {
            this(null, hostAddress, serverAddress, List.of(), false);
        }

        @Deprecated
        public ModpackAddresses(InetSocketAddress hostAddress, InetSocketAddress serverAddress, boolean ignoredRequiresMagic) {
            this(null, hostAddress, serverAddress, List.of(), false);
        }

        public ModpackAddresses(String endpointId, InetSocketAddress hostAddress, InetSocketAddress serverAddress) {
            this(endpointId, hostAddress, serverAddress, List.of(), false);
        }

        public ModpackAddresses(String endpointId, InetSocketAddress hostAddress, InetSocketAddress serverAddress, boolean shareMinecraftConnection) {
            this(endpointId, hostAddress, serverAddress, List.of(), shareMinecraftConnection);
        }

        @Deprecated
        public ModpackAddresses(String endpointId, InetSocketAddress hostAddress, InetSocketAddress serverAddress, boolean ignoredRequiresMagic, boolean shareMinecraftConnection) {
            this(endpointId, hostAddress, serverAddress, List.of(), shareMinecraftConnection);
        }

        public ModpackAddresses(String endpointId, InetSocketAddress hostAddress, InetSocketAddress serverAddress, List<InetSocketAddress> directAddresses, boolean shareMinecraftConnection) {
            this.endpointId = endpointId;
            this.directAddresses = directAddresses == null ? new ArrayList<>() : new ArrayList<>(directAddresses);
            this.hostAddress = hostAddress;
            this.serverAddress = serverAddress;
            this.shareMinecraftConnection = shareMinecraftConnection;
        }

        @Deprecated
        public ModpackAddresses(String endpointId, InetSocketAddress hostAddress, InetSocketAddress serverAddress, List<InetSocketAddress> directAddresses, boolean ignoredRequiresMagic, boolean shareMinecraftConnection) {
            this(endpointId, hostAddress, serverAddress, directAddresses, shareMinecraftConnection);
        }

        public boolean isAnyEmpty() {
            return serverAddress == null
                    || serverAddress.getHostString().isBlank()
                    || (!hasIrohEndpoint() && !hasBootstrapAddress());
        }

        public boolean hasIrohEndpoint() {
            return endpointId != null && !endpointId.isBlank();
        }

        public boolean hasBootstrapAddress() {
            return hostAddress != null && !hostAddress.getHostString().isBlank() && hostAddress.getPort() > 0;
        }

        public boolean hasDirectAddresses() {
            return directAddresses != null && !directAddresses.isEmpty();
        }

        public boolean hasNettyFallback() {
            return hasBootstrapAddress();
        }
    }

    @Deprecated
    public static PersistedModpackConnection legacyToPersistedModpackConnection(ModpackAddresses addresses) {
        if (addresses == null) {
            return null;
        }

        return new PersistedModpackConnection(
            addresses.serverAddress,
            new PersistedIrohAddressBook(
                addresses.endpointId,
                addresses.hostAddress,
                List.of(),
                System.currentTimeMillis()
            )
        );
    }

    public static class PersistedIrohAddressBook {
        public String endpointId;
        public transient List<InetSocketAddress> directIpAddresses;
        public InetSocketAddress rawTcp;
        public long savedAt;

        public PersistedIrohAddressBook() {
        }

        public PersistedIrohAddressBook(String endpointId, InetSocketAddress rawTcp, List<InetSocketAddress> directIpAddresses, long savedAt) {
            this.endpointId = endpointId;
            this.directIpAddresses = null;
            this.rawTcp = rawTcp;
            this.savedAt = savedAt;
        }

        public boolean hasEndpointId() {
            return endpointId != null && !endpointId.isBlank();
        }

        public boolean hasRawTcp() {
            return rawTcp != null && !rawTcp.getHostString().isBlank() && rawTcp.getPort() > 0;
        }

        public boolean hasDirectIpAddresses() {
            return directIpAddresses != null && !directIpAddresses.isEmpty();
        }
    }

    public static class PersistedModpackConnection {
        public InetSocketAddress minecraftServerAddress;
        public PersistedIrohAddressBook lastSuccessfulAddressBook;

        public PersistedModpackConnection() {
        }

        public PersistedModpackConnection(InetSocketAddress minecraftServerAddress, PersistedIrohAddressBook lastSuccessfulAddressBook) {
            this.minecraftServerAddress = minecraftServerAddress;
            this.lastSuccessfulAddressBook = lastSuccessfulAddressBook;
        }

        public boolean hasUsableAddressBook() {
            return minecraftServerAddress != null
                && lastSuccessfulAddressBook != null
                && lastSuccessfulAddressBook.hasEndpointId();
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
        public String addressToSend = "";
        public int portToSend = -1;
        public boolean shareMinecraftConnection = true;
        public boolean updateIpsOnEveryStart = false;
        public int bandwidthLimit = 0;
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

    public static class ServerCoreConfigFields {
        public String automodpackVersion = "";
        public String loader = "";
        public String loaderVersion = "";
        public String mcVersion = "";
    }

    public enum DnssecStatus {
        SECURE_MATCH,
        SECURE_MISMATCH,
        BOGUS,
        INSECURE,
        NO_RECORD,
        NXDOMAIN,
        MALFORMED,
        UNAVAILABLE,
        SKIPPED_IP_LITERAL
    }

    public static class DnssecDomainRecord {
        public String hostname;
        public String txtName;
        public String endpointId;
        public DnssecStatus status;
        public long checkedAt;
        public String reason;

        public DnssecDomainRecord() {
        }

        public DnssecDomainRecord(String hostname, String txtName, String endpointId, DnssecStatus status, long checkedAt, String reason) {
            this.hostname = hostname;
            this.txtName = txtName;
            this.endpointId = endpointId;
            this.status = status;
            this.checkedAt = checkedAt;
            this.reason = reason;
        }
    }

    public static class TrustedEndpointRecord {
        public String endpointId;
        public TrustEvidence trustEvidence = TrustEvidence.NONE;
        public long trustedAt;
        public Map<String, DnssecDomainRecord> dnssecDomains = new LinkedHashMap<>();

        public TrustedEndpointRecord() {
        }

        public TrustedEndpointRecord(String endpointId, TrustEvidence trustEvidence, long trustedAt, Map<String, DnssecDomainRecord> dnssecDomains) {
            this.endpointId = endpointId;
            this.trustEvidence = trustEvidence == null ? TrustEvidence.NONE : trustEvidence;
            this.trustedAt = trustedAt;
            this.dnssecDomains = dnssecDomains == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dnssecDomains);
        }

        public void normalize() {
            if (trustEvidence == null) {
                trustEvidence = TrustEvidence.NONE;
            }
            if (dnssecDomains == null) {
                dnssecDomains = new LinkedHashMap<>();
            }
        }
    }

    public static class KnownHostsFieldsV2 {
        public Map<String, TrustedEndpointRecord> trustedEndpoints = new LinkedHashMap<>();
        public transient Map<String, String> endpointIds; // legacy migration-only field

        public void normalize() {
            if (trustedEndpoints == null) {
                trustedEndpoints = new LinkedHashMap<>();
            }
            trustedEndpoints.values().removeIf(Objects::isNull);
            trustedEndpoints.values().forEach(TrustedEndpointRecord::normalize);
        }
    }

    public static KnownHostsFieldsV2 loadKnownHostsV2(String json) {
        KnownHostsFieldsV2 knownHosts = new KnownHostsFieldsV2();
        if (json == null || json.isBlank()) {
            return knownHosts;
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (Exception e) {
            return knownHosts;
        }

        if (!parsed.isJsonObject()) {
            return knownHosts;
        }

        JsonObject root = parsed.getAsJsonObject();
        JsonElement trustedEndpointsElement = root.get("trustedEndpoints");
        if (isLegacyTrustedEndpointsMap(trustedEndpointsElement)) {
            migrateLegacyTrustedEndpoints(knownHosts.trustedEndpoints, trustedEndpointsElement.getAsJsonObject());
        } else if (trustedEndpointsElement != null && trustedEndpointsElement.isJsonObject()) {
            Type trustedEndpointsType = new TypeToken<Map<String, TrustedEndpointRecord>>() { }.getType();
            Map<String, TrustedEndpointRecord> decoded = ConfigTools.GSON.fromJson(trustedEndpointsElement, trustedEndpointsType);
            if (decoded != null) {
                knownHosts.trustedEndpoints.putAll(decoded);
            }
        }

        JsonElement endpointIdsElement = root.get("endpointIds");
        if (endpointIdsElement != null && endpointIdsElement.isJsonObject()) {
            migrateLegacyTrustedEndpoints(knownHosts.trustedEndpoints, endpointIdsElement.getAsJsonObject());
        }

        knownHosts.normalize();
        return knownHosts;
    }

    private static boolean isLegacyTrustedEndpointsMap(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return false;
        }

        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                return false;
            }
        }

        return true;
    }

    private static void migrateLegacyTrustedEndpoints(Map<String, TrustedEndpointRecord> target, JsonObject source) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                continue;
            }

            String endpointId = entry.getValue().getAsString();
            if (endpointId == null || endpointId.isBlank()) {
                continue;
            }

            target.putIfAbsent(entry.getKey(), new TrustedEndpointRecord(endpointId, TrustEvidence.TOFU_KNOWN, 0L, Map.of()));
        }
    }

    public static class KnownHostsFields {
        public Map<String, String> trustedEndpoints; // canonical server address, endpoint id
        public transient Map<String, String> endpointIds; // legacy migration-only field
    }

    public static class PlayerEndpointsFields {
        public Map<String, PlayerEndpointRecord> byUuid = new HashMap<>();
    }

    public static class PlayerEndpointRecord {
        public String endpointId;
        public String lastKnownName;
        public long firstSeenAt;
        public long lastSeenAt;

        public PlayerEndpointRecord() {
        }

        public PlayerEndpointRecord(String endpointId, String lastKnownName, long firstSeenAt, long lastSeenAt) {
            this.endpointId = endpointId;
            this.lastKnownName = lastKnownName;
            this.firstSeenAt = firstSeenAt;
            this.lastSeenAt = lastSeenAt;
        }
    }

    public static class ModpackContentFields {
        public String modpackName = "";
        public String automodpackVersion = "";
        public String loader = "";
        public String loaderVersion = "";
        public String mcVersion = "";
        public Set<ModpackContentItem> list;
        public Set<FileToDelete> nonModpackFilesToDelete = Set.of();

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
            public final boolean forceCopy;
            public final String sha1;
            public final String murmur;

            public ModpackContentItem(String file, String size, String type, boolean editable, boolean forceCopy, String sha1, String murmur) {
                this.file = file;
                this.size = size;
                this.type = type;
                this.editable = editable;
                this.forceCopy = forceCopy;
                this.sha1 = sha1;
                this.murmur = murmur;
            }

            @Override
            public String toString() {
                return String.format("ModpackContentItems(file=%s, size=%s, type=%s, editable=%s, forceCopy=%s, sha1=%s, murmur=%s)", file, size, type, editable, forceCopy, sha1, murmur);
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
            public final long lastSize;     // Local disk size
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
}
