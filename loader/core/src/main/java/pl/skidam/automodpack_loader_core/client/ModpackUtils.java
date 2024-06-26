package pl.skidam.automodpack_loader_core.client;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.Url;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static pl.skidam.automodpack_core.config.ConfigTools.GSON;
import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ModpackUtils {
    public static boolean isUpdate(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            throw new IllegalArgumentException("Server modpack content list is null");
        }

        // get client modpack content
        var optionalClientModpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);
        if (optionalClientModpackContentFile.isPresent() && Files.exists(optionalClientModpackContentFile.get())) {

            Jsons.ModpackContentFields clientModpackContent = ConfigTools.loadConfig(optionalClientModpackContentFile.get(), Jsons.ModpackContentFields.class);

            if (clientModpackContent == null) {
                return true;
            }

            LOGGER.info("Checking files...");
            for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : serverModpackContent.list) {
                String file = modpackContentField.file;
                String serverSHA1 = modpackContentField.sha1;

                Path path = Path.of(modpackDir + file);

                if (modpackContentField.editable && Files.exists(path)) {
                    continue;
                }

                if (!Files.exists(path)) {
                    path = Path.of("." + file);
                    if (!Files.exists(path)) {
                        LOGGER.info("File does not exists {}", path);
                        return true;
                    }
                }

                if (!Objects.equals(serverSHA1, CustomFileUtils.getHash(path, "sha1").orElse(null))) {
                    LOGGER.info("File does not match hash {}", path);
                    return true;
                }
            }

            // Server also might have deleted some files
            for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : clientModpackContent.list) {
                if (serverModpackContent.list.stream().noneMatch(serverModpackContentItem -> serverModpackContentItem.sha1.equals(modpackContentField.sha1))) {
                    LOGGER.info("File does not exist on server {}", modpackContentField.file);
                    return true;
                }
            }

            LOGGER.info("{} is up to date!", modpackDir);
            return false;
        } else {
            return true;
        }
    }

    public static void correctFilesLocations(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, List<String> ignoreFiles) throws IOException {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return;
        }

        // correct the files locations
        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String file = contentItem.file;

            if (ignoreFiles.contains(file)) continue;

            Path modpackFile = Paths.get(modpackDir + file);
            Path runFile = Paths.get("." + file);

            boolean modpackFileExists = Files.exists(modpackFile);
            boolean runFileExists = Files.exists(runFile);

            if (modpackFileExists && !runFileExists) {
                // Don't copy from modpack to run if it's a mod
                if (contentItem.type != null && contentItem.type.equals("mod")) continue;
                CustomFileUtils.copyFile(modpackFile, runFile);
            } else if (!modpackFileExists && runFileExists) {
                CustomFileUtils.copyFile(runFile, modpackFile);
            } else if (!modpackFileExists) {
                LOGGER.error("File " + file + " doesn't exist!?");
            }
        }
    }


    // Checks if in default mods folder are any mods that are in modpack
    // Returns map of modpack mods and default mods that have the same mod id they dont necessarily have to be the same*
    public static Map<LoaderService.Mod, LoaderService.Mod> getDupeMods(Path modpackPath) throws IOException {
        // maybe also check subfolders...
        final List<Path> defaultMods = Files.list(Path.of("./mods")).toList(); // TODO replace this with standardized mods path
        final List<Path> modpackMods = Files.list(modpackPath.resolve("mods")).toList();
        final Collection<LoaderService.Mod> defaultModList = defaultMods.stream().map(modPath -> LOADER_MANAGER.getMod(modPath)).filter(Objects::nonNull).toList();
        final Collection<LoaderService.Mod> modpackModList = modpackMods.stream().map(modPath -> LOADER_MANAGER.getMod(modPath)).filter(Objects::nonNull).toList();

        if (defaultModList.isEmpty() || modpackModList.isEmpty()) return Map.of();

        final Map<LoaderService.Mod, LoaderService.Mod> duplicates = new HashMap<>();

        for (LoaderService.Mod modpackMod : modpackModList) {
            LoaderService.Mod defaultMod = defaultModList.stream().filter(mod -> mod.modID().equals(modpackMod.modID())).findFirst().orElse(null); // There might be super rare edge case if client would have for some reason more than one mod with the same mod id
            if (defaultMod != null) {
                duplicates.put(modpackMod, defaultMod);
            }
        }

        return duplicates;
    }

    // Returns true if removed any mod from default mods folder
    // if the client mod is a duplicate of what modpack contains then it removes it from client so that you dont need to restart game just when you launched it and modpack get updated - basically having these mods separately allows for seamless updates
    // if you have client mods which require specific mod which is also a duplicate of what modpack contains it should stay
    public static boolean removeDupeMods(Map<LoaderService.Mod, LoaderService.Mod> dupeMods) throws IOException {

        List<Path> defaultMods = Files.list(Path.of("./mods")).toList(); // TODO replace this with standardized mods path
        Collection<LoaderService.Mod> defaultModList = defaultMods.stream().map(modPath -> LOADER_MANAGER.getMod(modPath)).filter(Objects::nonNull).toList();

        if (defaultModList.isEmpty()) return false;

        List<String> defaultModDeps = new ArrayList<>();

        // Grabs dependencies of non dupe mods
        for (LoaderService.Mod defaultMod : defaultModList) {
            if (dupeMods.containsValue(defaultMod)) {
//                LOGGER.info("{} is a dupe mod of modpack", defaultMod.modID());
            } else {
                defaultModDeps.addAll(defaultMod.dependencies());
            }
        }

        boolean changedAnyThing = false;

        // Removes unnecessary dupe mods
        for (var dupeMod : dupeMods.entrySet()) {
            Path modpackModPath = dupeMod.getKey().modPath();
            Path defaultModPath = dupeMod.getValue().modPath();
            String modId = dupeMod.getKey().modID();
            Collection<String> providesIDs = dupeMod.getKey().providesIDs();
            List<String> IDs = new ArrayList<>();
            IDs.add(modId);
            IDs.addAll(providesIDs);

            boolean isDependent = false;

            // Check for mods like fabric api which provides more than one ID 'fabric-api' and 'fabric'
            for (String ID : IDs) {
                if (defaultModDeps.contains(ID)) {
                    isDependent = true;
                    break;
                }
            }

            if (isDependent) {
                // Check if hashes are the same, if not remove the mod and copy the modpack mod from modpack to make sure we achieve parity,
                // If we break mod compat there that's up to the user to fix it, because they added their own mods, we need to guarantee that server modpack is working.
                String modpackModHash = CustomFileUtils.getHash(modpackModPath, "sha1").orElse(null);
                String defaultModHash = CustomFileUtils.getHash(defaultModPath, "sha1").orElse(null);
                if (!Objects.equals(modpackModHash, defaultModHash)) {
                    LOGGER.warn("Changing dupe mod {} to modpack version of this mod", modId);
                    CustomFileUtils.forceDelete(defaultModPath);
                    CustomFileUtils.copyFile(modpackModPath, defaultModPath.getParent().resolve(modpackModPath.getFileName()));
                    changedAnyThing = true;
                }
            } else {
                LOGGER.warn("Removing dupe mod {}", modId);
                CustomFileUtils.forceDelete(defaultModPath);
                changedAnyThing = true;
            }
        }

        return changedAnyThing;
    }


    public static List<Path> renameModpackDir(Path modpackContentFile, Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        Jsons.ModpackContentFields clientModpackContent = ConfigTools.loadModpackContent(modpackContentFile);
        if (clientModpackContent != null) {
            String installedModpackName = clientModpackContent.modpackName;
            String serverModpackName = serverModpackContent.modpackName;

            if (!serverModpackName.equals(installedModpackName) && !serverModpackName.isEmpty()) {

                Path newModpackDir = Path.of(modpackDir.getParent() + File.separator + serverModpackName);

                try {
                    Files.move(modpackDir, newModpackDir, StandardCopyOption.REPLACE_EXISTING);

                    // TODO remove old modpack from list
                    addModpackToList(newModpackDir.getFileName().toString());
                    selectModpack(newModpackDir);

                    LOGGER.info("Changed modpack name of {} to {}", modpackDir.getFileName().toString(), serverModpackName);

                    modpackContentFile = Path.of(newModpackDir + File.separator + modpackContentFile.getFileName());

                    return List.of(newModpackDir, modpackContentFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return null;
    }

    // Returns true if value changed
    public static boolean selectModpack(Path modpackDir) {
        String modpackToSelect = modpackDir.getFileName().toString();
        String selectedModpack = clientConfig.selectedModpack;
        clientConfig.selectedModpack = modpackToSelect;
        ConfigTools.saveConfig(clientConfigFile, clientConfig);
        return !modpackToSelect.equals(selectedModpack);
    }

    public static void addModpackToList(String modpackName) {
        if (modpackName == null || modpackName.isEmpty()) {
            return;
        }

        if (clientConfig.installedModpacks == null) {
            clientConfig.installedModpacks = List.of(modpackName);
        } else if (!clientConfig.installedModpacks.contains(modpackName)) {
            LinkedList<String> newList = new LinkedList<>(clientConfig.installedModpacks);
            newList.add(modpackName);
            clientConfig.installedModpacks = newList;
        }

        ConfigTools.saveConfig(clientConfigFile, clientConfig);
    }

    // Returns modpack name formatted for path or url if server doesn't provide modpack name
    public static Path getModpackPath(String url, String modpackName) {

        String nameFromUrl = Url.removeHttpPrefix(url);

        if (FileInspection.isInValidFileName(nameFromUrl)) {
            nameFromUrl = FileInspection.fixFileName(nameFromUrl);
        }

        Path modpackDir = Path.of(modpacksDir + File.separator + nameFromUrl);

        if (!modpackName.isEmpty()) {
            // Check if we don't have already installed modpack via this link
            if (clientConfig.installedModpacks != null && clientConfig.installedModpacks.contains(nameFromUrl)) {
                return modpackDir;
            }

            String nameFromName = modpackName;

            if (FileInspection.isInValidFileName(modpackName)) {
                nameFromName = FileInspection.fixFileName(modpackName);
            }

            modpackDir = Path.of(modpacksDir + File.separator + nameFromName);
        }

        return modpackDir;
    }

    public static Optional<Jsons.ModpackContentFields> requestServerModpackContent(String link) {
        if (link == null) {
            throw new IllegalArgumentException("Link is null");
        }

        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(link).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
            connection.connect();

            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String contentResponse = response.toString();

                Jsons.ModpackContentFields serverModpackContent = GSON.fromJson(contentResponse, Jsons.ModpackContentFields.class);

                if (serverModpackContent == null) {
                    LOGGER.error("Couldn't connect to modpack server " + link);
                    return Optional.empty();
                }

                if (serverModpackContent.list.isEmpty()) {
                    LOGGER.error("Modpack content is empty!");
                    return Optional.empty();
                }

                if (!potentiallyMalicious(serverModpackContent)) {
                    return Optional.of(serverModpackContent);
                }
            } else {
                LOGGER.error("Couldn't connect to modpack server " + link + ", Response Code: " + responseCode);
            }

        } catch (SocketException | SocketTimeoutException e) {
            LOGGER.error("Couldn't connect to modpack server " + link);
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content");
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return Optional.empty();
    }

    // check if modpackContent is valid/isn't malicious
    public static boolean potentiallyMalicious(Jsons.ModpackContentFields serverModpackContent) {
        String modpackName = serverModpackContent.modpackName;
        if (modpackName.contains("../") || modpackName.contains("/..")) {
            LOGGER.error("Modpack content is invalid, it contains /../ in modpack name");
            return true;
        }

        for (var modpackContentItem : serverModpackContent.list) {
            String file = modpackContentItem.file.replace("\\", "/");
            if (file.contains("../") || file.contains("/..")) {
                LOGGER.error("Modpack content is invalid, it contains /../ in file name of {}", file);
                return true;
            }
        }

        return false;
    }

    public static Optional<Jsons.ModpackContentFields> refreshServerModpackContent(String link, String body) {
        // send custom http body request to get modpack content, rest the same as getServerModpackContent
        if (link == null || body == null) {
            throw new IllegalArgumentException("Link or body is null");
        }

        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(link + "refresh").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000); // bigger timout to give server enough time to refresh modpack content, it shouldn't anyways in most cases take more than one second
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
            connection.setDoOutput(true);
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            connection.connect();

            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String contentResponse = response.toString();

                Jsons.ModpackContentFields serverModpackContent = GSON.fromJson(contentResponse, Jsons.ModpackContentFields.class);

                if (serverModpackContent == null) {
                    LOGGER.error("Couldn't connect to modpack server " + link);
                    return Optional.empty();
                }

                if (serverModpackContent.list.isEmpty()) {
                    LOGGER.error("Modpack content is empty!");
                    return Optional.empty();
                }

                // check if modpackContent is valid/isn't malicious
                for (var modpackContentItem : serverModpackContent.list) {
                    String file = modpackContentItem.file.replace("\\", "/");
                    if (file.contains("../") || file.contains("/..")) {
                        LOGGER.error("Modpack content is invalid, it contains /../ in file name or url");
                        return Optional.empty();
                    }
                }

                return Optional.of(serverModpackContent);
            } else {
                LOGGER.error("Couldn't connect to modpack server " + link + ", Response Code: " + responseCode);
            }
        } catch (ConnectException | SocketTimeoutException e) {
            LOGGER.error("Couldn't connect to modpack server " + link);
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content");
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return Optional.empty();

    }
}
