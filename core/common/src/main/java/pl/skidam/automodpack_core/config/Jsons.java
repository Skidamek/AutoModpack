/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack_core.config;

import pl.skidam.automodpack_core.Loader;

import java.util.Arrays;
import java.util.List;

public class Jsons {

    public static class ClientConfigFields {
        public String selectedModpack = "";
//        public boolean forceDangerScreen = true;
        public boolean selfUpdater = true;
    }

    public static class ServerConfigFields {
        public String modpackName = "";
        public boolean modpackHost = true;
        public boolean generateModpackOnStart = true;
        public List<String> syncedFiles = Arrays.asList("/mods/", "/config/");
        public List<String> excludeSyncedFiles = Arrays.asList("/mods/iDontWantThisModInModpack.jar", "/config/andThisConfigToo.json", "/mods/andAllTheseMods-*.jar");
        public List<String> allowEditsInFiles = Arrays.asList("/options.txt");
//        public List<String> forceLoad = List.asList("/resourcepacks/someResourcePack.zip", "/shaderpacks/someShaderPack.zip");
        public boolean optionalModpack = false;
        public boolean autoExcludeServerSideMods = true;
//        public boolean velocityMode = false; compat plugin :)
        // public boolean forceToDisableAllOtherModsOnClients = false;
        public int hostThreads = 8;
        public String hostIp = "";
        public String hostLocalIp = "";
        public boolean updateIpsOnEveryStart = false;
        public int hostPort = 30037;
        public boolean reverseProxy = false;
        public String externalModpackHostLink = "";
        public boolean selfUpdater = true;
        public List<String> acceptedLoaders = Arrays.asList(new Loader().getPlatformType().toString().toLowerCase());
    }

    public static class ModpackContentFields {
        public String modpackName = "";
        public String link = "";
        public String automodpackVersion = "";
        public String loader = "";
        public String loaderVersion = "";
        public String mcVersion = "";
        public String modpackHash = "";
        public List<ModpackContentItem> list;
        public ModpackContentFields(String link, List<ModpackContentItem> list) {
            this.link = link; // Set it on the client side only
            this.list = list;
        }
        public static class ModpackContentItem {
            public String file;
            public String link; // if automodpack host (file == link) else (file != link) file is a path, link is a url
            public String size;
            public String type;
            public boolean editable;
            public String modId;
            public String version;
            public String sha1;
            public String murmur;

            public ModpackContentItem(String file, String link, String size, String type, boolean editable, String modId, String version, String sha1, String murmur) {
                this.file = file;
                this.link = link;
                this.size = size;
                this.type = type;
                this.editable = editable;
                this.modId = modId;
                this.version = version;
                this.sha1 = sha1;
                this.murmur = murmur;
            }

            @Override
            public String toString() {
                return String.format("ModpackContentItems(file=%s, link=%s, size=%s, sha1%s, murmur%s)", file, link, size, sha1, murmur);
            }
        }
    }
}
