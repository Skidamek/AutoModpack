package pl.skidam.automodpack_core.config;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import pl.skidam.automodpack_core.auth.Secrets;

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
        public boolean updateSelectedModpackOnLaunch = true;
        public boolean selfUpdater = false;
        public boolean syncAutoModpackVersion = true;
        public boolean syncLoaderVersion = true;
        public boolean playMusic = true;
        public boolean allowRemoteNonModpackDeletions = true;
    }


    public static class ModpackAddresses {
        public InetSocketAddress hostAddress; // modpack host address
        public InetSocketAddress serverAddress; // minecraft server address
        public boolean requiresMagic; // if true, client will use magic packets to connect to the modpack host

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
        public ModpackAddresses(InetSocketAddress hostAddress, InetSocketAddress serverAddress, boolean requiresMagic) {
            this.hostAddress = hostAddress;
            this.serverAddress = serverAddress;
            this.requiresMagic = requiresMagic;
        }

        public boolean isAnyEmpty() {
            return (hostAddress == null || serverAddress == null || hostAddress.getHostString().isBlank() || serverAddress.getHostString().isBlank());
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
        public boolean disableInternalTLS = false;
        public boolean requireMagicPackets = false;
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
        public Map<String, GroupDeclaration> groups = Map.of(
                "main", mainGroupDeclaration()
        );
        public Map<String, String> nonModpackFilesToDelete = Map.of(); // FileToDelete but without timestamp
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
        public boolean disableInternalTLS = false;
        public boolean requireMagicPackets = false;
        public boolean updateIpsOnEveryStart = false;
        public int bandwidthLimit = 0;
        public boolean validateSecrets = true;
        public long secretLifetime = 336; // 336 hours = 14 days
        public boolean selfUpdater = false;
        public Set<String> acceptedLoaders = new HashSet<>();
    }

    public static GroupDeclaration mainGroupDeclaration() {
        GroupDeclaration decl = new GroupDeclaration();
        decl.required = true;
        decl.recommended = true;
        decl.syncedFiles = List.of("/mods/*.jar", "/kubejs/**", "!/kubejs/server_scripts/**", "/emotes/*");
        decl.allowEditsInFiles = List.of("/options.txt", "/config/**");
        return decl;
    }

    // TODO see that we changed set to list for
    public static class GroupDeclaration {

        // UI Metadata
        public String displayName = ""; // Its already as a map key (group id / file path) but the visible might me different
        public String description = ""; // e.g., "Increases FPS using Sodium/Lithium"

        // Logic Flags
        public boolean required = false; // If true, user cannot uncheck (with the question above it seems that if client may opt for a different required pack if both are breaking each other)
        public boolean recommended = false; // Selected by default, if required this option doesn't matter
        public boolean selective = false; // If true, the user can pick-and-choose individual files else the group is "all-or-nothing"

        // Dependency & Compatibility
        public List<String> breaksWith = List.of(); // e.g., ["optifine-group"]
        public List<String> requires = List.of(); // e.g., ["fabric-api-group"]

        // OS Compatibility (Enum: WINDOWS, LINUX, MACOS, ANDROID)
        public List<String> compatibleOS = List.of(); // Empty = All OS

        // File Scanning Rules (Per Group)
        public List<String> syncedFiles = List.of(); // Relative to group folder
        public List<String> allowEditsInFiles = List.of();
        public List<String> forceCopyFilesToStandardLocation = List.of();
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

        public Map<String, String> hosts; // host, fingerprint
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

    public static class ModpackContent {

        public String modpackName = "";
        public String automodpackVersion;
        public String loader;
        public String loaderVersion;
        public String mcVersion;

        public Map<String, ModpackGroupFields> groups;
        public Set<FileToDelete> nonModpackFilesToDelete;


        public ModpackContent(Map<String, ModpackGroupFields> groups) {
            this.groups = groups;
            this.nonModpackFilesToDelete = new HashSet<>();
        }

        public ModpackContent() {
            this.groups = new HashMap<>();
            this.nonModpackFilesToDelete = new HashSet<>();
        }
    }

    public static class ModpackGroupFields {

        // These values map directly from config - Jsons.GroupDeclaration
        public String displayName;
        public String description;
        public boolean required;
        public boolean recommended;
        public boolean selective;
        public List<String> breaksWith;
        public List<String> requires;
        public List<String> compatibleOS;

        // This part is dynamicly generated
        public Set<ModpackContentItem> files = new HashSet<>();

        public ModpackGroupFields() {
            this.files = new HashSet<>();
        }

        public ModpackGroupFields(String displayName, String description, boolean required, boolean recommended, boolean selective, List<String> breaksWith, List<String> requires, List<String> compatibleOS) {
            this.displayName = displayName;
            this.description = description;
            this.required = required;
            this.recommended = recommended;
            this.selective = selective;
            this.breaksWith = breaksWith;
            this.requires = requires;
            this.compatibleOS = compatibleOS;
        }

        public ModpackGroupFields(Jsons.GroupDeclaration groupDeclaration) {
            this.displayName = groupDeclaration.displayName;
            this.description = groupDeclaration.description;
            this.required = groupDeclaration.required;
            this.recommended = groupDeclaration.recommended;
            this.selective = groupDeclaration.selective;
            this.breaksWith = groupDeclaration.breaksWith;
            this.requires = groupDeclaration.requires;
            this.compatibleOS = groupDeclaration.compatibleOS;
        }
    }

    public static class ModpackContentItem {

        public final String file;
        public final long size;
        public final String type;
        public final boolean editable;
        public final boolean forceCopy;
        public final String sha1;
        public final String murmur;

        public ModpackContentItem(String file, long size, String type, boolean editable, boolean forceCopy, String sha1, String murmur) {
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

    public static class ClientSelectionManagerFields {
        public String selectedPack;
        public Map<String, Modpack> modpacks = new HashMap<>();

        public static class Modpack {
            public ModpackAddresses modpackAddresses;
            public List<Group> selectedGroups;

            public Modpack(ModpackAddresses modpackAddresses) {
                this.modpackAddresses = modpackAddresses;
                this.selectedGroups = new ArrayList<>();
            }

            public Modpack(ModpackAddresses modpackAddresses, List<Group> selectedGroups) {
                this.modpackAddresses = modpackAddresses;
                this.selectedGroups = selectedGroups;
            }
        }

        public static class Group {
            public final String groupId;
            public final List<String> selectedFiles;

            public Group(String groupId, List<String> selectedFiles) {
                this.groupId = groupId;
                this.selectedFiles = selectedFiles;
            }
        }
    }
}
