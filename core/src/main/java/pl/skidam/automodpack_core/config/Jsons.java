
package pl.skidam.automodpack_core.config;

import pl.skidam.automodpack_core.auth.Secrets;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        public boolean syncLoaderVersion = false;
        public boolean playMusic = true;
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
            return hostAddress == null || serverAddress == null || hostAddress.getHostString().isBlank() || serverAddress.getHostString().isBlank();
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
        public List<String> syncedFiles = List.of("/mods/*.jar", "/kubejs/**", "!/kubejs/server_scripts/**", "/emotes/*");
        public List<String> allowEditsInFiles = List.of("/options.txt", "/config/**");
        public List<String> forceCopyFilesToStandardLocation = List.of();
        public Map<String, String> filesToDeleteOnClient = Map.of();
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
        public List<String> acceptedLoaders;
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

    public static class ModpackContentFields {
        public String modpackName = "";
        public String automodpackVersion = "";
        public String loader = "";
        public String loaderVersion = "";
        public String mcVersion = "";
        public Set<ModpackContentItem> list;
        public Map<String, String> filesToDeleteOnClient = Map.of();

        public ModpackContentFields(Set<ModpackContentItem> list) {
            this.list = list;
        }

        public ModpackContentFields() {
            this.list = Set.of();
        }

        public static class ModpackContentItem {
            public String file;
            public String size;
            public String type;
            public boolean editable;
            public boolean forceCopy;
            public String sha1;
            public String murmur;

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
                return String.format("ModpackContentItems(file=%s, size=%s, type=%s, editable=%s, sha1=%s, murmur=%s)", file, size, type, editable, sha1, murmur);
            }
        }
    }

    // seems kinda too verbose and it may take too much space for large modpack but lets keep it for now
    public static class LocalMetadata {
        // Map of File Path -> Fingerprint
        public Map<String, FileFingerprint> files = new ConcurrentHashMap<>();

        public static class FileFingerprint {
            public String sha1;
            public long lastSize;     // Local disk size
            public long lastModified; // Local disk timestamp

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
}
