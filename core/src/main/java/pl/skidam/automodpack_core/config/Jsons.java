
package pl.skidam.automodpack_core.config;

import pl.skidam.automodpack_core.auth.Secrets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Jsons {

    public static class ClientConfigFields {
        public int DO_NOT_CHANGE_IT = 1; // file version
        public String selectedModpack = ""; // modpack name
        public Map<String, String> installedModpacks; // modpack name, host
        public boolean selfUpdater = false;
    }

    public static class ServerConfigFields {
        public int DO_NOT_CHANGE_IT = 1; // file version
        public String modpackName = "";
        public boolean modpackHost = true;
        public boolean generateModpackOnStart = true;
        public List<String> syncedFiles = List.of("/mods/*.jar", "!/mods/iDontWantThisModInModpack.jar", "!/config/andThisConfigToo.json", "!/mods/andAllTheseMods-*.jar", "!/mods/server-*.jar");
        public List<String> allowEditsInFiles = List.of("/options.txt", "/config/*", "!/config/excludeThisFile");
        public boolean autoExcludeUnnecessaryFiles = true;
//        public List<String> forceLoad = List.of("/resourcepacks/someResourcePack.zip", "/shaderpacks/someShaderPack.zip");
//        public List<List<String>> forceLoad = new ArrayList<>();
        public boolean requireAutoModpackOnClient = true;
        public boolean nagUnModdedClients = true;
        public String nagMessage = "This server provides dedicated modpack through AutoModpack!";
        public String nagClickableMessage = "Click here to get the AutoModpack!";
        public String nagClickableLink = "https://modrinth.com/project/automodpack";
        public boolean autoExcludeServerSideMods = true;
//        public boolean velocityMode = false; compat plugin... someday I hope
//        public boolean forceToDisableAllOtherModsOnClients = false;
        public boolean hostModpackOnMinecraftPort = true;
        public String hostIp = "";
        public String hostLocalIp = "";
        public boolean updateIpsOnEveryStart = false;
        public int hostPort = -1;
        public boolean reverseProxy = false;
        public int bandwidthLimit = 0;
        public String fingerprint = "";
        public long secretLifetime = 336; // 336 hours = 14 days
        public boolean validateSecrets = true;
        public boolean selfUpdater = false;
        public List<String> acceptedLoaders;
    }

    public static class ServerCoreConfigFields {
        public String automodpackVersion = "4.0.0-beta29"; // TODO: dont hardcode it
        public String loader = "fabric";
        public String loaderVersion = "0.16.10";
        public String mcVersion = "1.21.1";
    }

    public static class WorkaroundFields {
        public int DO_NOT_CHANGE_IT = 1; // file version
        public Set<String> workaroundMods;
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
            public String sha1;
            public String murmur;

            public ModpackContentItem(String file, String size, String type, boolean editable, String sha1, String murmur) {
                this.file = file;
                this.size = size;
                this.type = type;
                this.editable = editable;
                this.sha1 = sha1;
                this.murmur = murmur;
            }

            @Override
            public String toString() {
                return String.format("ModpackContentItems(file=%s, size=%s, type=%s, editable=%s, sha1=%s, murmur=%s)", file, size, type, editable, sha1, murmur);
            }
        }
    }
}
