
package pl.skidam.automodpack_core.config;

import java.util.List;


public class Jsons {

    public static class ClientConfigFields {
        public String selectedModpack = "";
        public List<String> installedModpacks;
        public boolean selfUpdater = false;
    }

    public static class ServerConfigFields {
        public String modpackName = "";
        public boolean modpackHost = true;
        public boolean generateModpackOnStart = true;
        public List<String> syncedFiles = List.of("/mods/*.jar", "!/mods/iDontWantThisModInModpack.jar", "!/config/andThisConfigToo.json", "!/mods/andAllTheseMods-*.jar", "!/mods/server-*.jar");
        public List<String> allowEditsInFiles = List.of("/options.txt", "/config/*", "!/config/excludeThisFile");
//        public List<String> forceLoad = List.of("/resourcepacks/someResourcePack.zip", "/shaderpacks/someShaderPack.zip");
//        public List<List<String>> forceLoad = new ArrayList<>();
        public boolean requireAutoModpackOnClient = true;
        public boolean nagUnModdedClients = true;
        public String nagMessage= "This server provides dedicated modpack through AutoModpack!";
        public boolean autoExcludeServerSideMods = true;
//        public boolean velocityMode = false; compat plugin... someday I hope
//        public boolean forceToDisableAllOtherModsOnClients = false;
        public boolean hostModpackOnMinecraftPort = true;
        public String hostIp = "";
        public String hostLocalIp = "";
        public boolean updateIpsOnEveryStart = false;
        public int hostPort = -1;
        public boolean reverseProxy = false;
        public boolean selfUpdater = false; // consider defaulting it to true
        public List<String> acceptedLoaders;
    }

    public static class ModpackContentFields {
        public String modpackName = "";
        public String link = "";
        public String automodpackVersion = "";
        public String loader = "";
        public String loaderVersion = "";
        public String mcVersion = "";
        public List<ModpackContentItem> list;
        public ModpackContentFields(String link, List<ModpackContentItem> list) {
            this.link = link; // Set it on the client side only
            this.list = list;
        }

        public ModpackContentFields() {
            this.link = "";
            this.list = List.of();
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
