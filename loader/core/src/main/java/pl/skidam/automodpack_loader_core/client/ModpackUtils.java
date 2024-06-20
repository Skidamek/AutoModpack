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

                Path path = Path.of(modpackDir + File.separator + file);

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

    public record MOD_TO_MOD(LoaderService.Mod modpackMod, LoaderService.Mod defaultMod) { }

    // Checks if in default mods folder are any mods that are in modpack
    // Returns map of modpack mods and default mods that have the same mod id they dont necessarily have to be the same*
    public static List<MOD_TO_MOD> getDupeMods(Path modpackPath) throws IOException {
        // maybe also check subfolders...
        final List<Path> defaultMods = Files.list(Path.of("./mods")).toList(); // TODO replace this with standardized mods path
        final List<Path> modpackMods = Files.list(modpackPath.resolve("mods")).toList();
        final Collection<LoaderService.Mod> defaultModList = defaultMods.stream().map(modPath -> LOADER_MANAGER.getMod(modPath)).filter(Objects::nonNull).toList();
        final Collection<LoaderService.Mod> modpackModList = modpackMods.stream().map(modPath -> LOADER_MANAGER.getMod(modPath)).filter(Objects::nonNull).toList();

        if (defaultModList.isEmpty() || modpackModList.isEmpty()) return List.of();

        final List<MOD_TO_MOD> duplicates = new ArrayList<>();


        for (LoaderService.Mod modpackMod : modpackModList) {
            LoaderService.Mod defaultMod = defaultModList.stream().filter(mod -> mod.modID().equals(modpackMod.modID())).findFirst().orElse(null);
            if (defaultMod != null) {
                duplicates.add(new MOD_TO_MOD(modpackMod, defaultMod));
            }
        }

        duplicates.forEach((MOD_TO_MOD) -> LOGGER.info("Modpack mod: {} | Default mod: {}", MOD_TO_MOD.modpackMod.modPath(), MOD_TO_MOD.defaultMod.modPath()));

        return duplicates;
    }

    // Checks if other mods in path are dependent on provided mod
    // Returns true if other mods are dependent on provided mod and false otherwise
    public static List<String> checkIfThereAreDepsFor(LoaderService.Mod modToCheck, Path modsPath) throws IOException {
        final List<Path> mods = Files.list(modsPath).toList();
        final Collection<LoaderService.Mod> modList = mods.stream().map(modPath -> LOADER_MANAGER.getMod(modPath)).filter(Objects::nonNull).toList();

        if (modList.isEmpty()) return List.of();

        final List<String> modDeps = new ArrayList<>();

        for (LoaderService.Mod mod : modList) {
            modDeps.addAll(mod.dependencies());
            LOGGER.info("Dependencies of {}: {}", mod.modID(), mod.dependencies());
        }

        if (modDeps.contains(modToCheck.modID())) {
            LOGGER.error("Other mods are dependent on {}", modToCheck.modID());
            return modDeps;
        }

        LOGGER.warn("No other mods are dependent on {}", modToCheck.modID());
        return modDeps;
    }

    // Returns true if removed any mod from default mods folder
    public static boolean removeDupeMods(List<MOD_TO_MOD> dupeMods) throws IOException {
        boolean changedAnyThing = false;
        LOGGER.info("Removing duplicate mods from default mods folder");

        List<String> dupeModDepsList = new ArrayList<>();

        for (MOD_TO_MOD dupeMod : dupeMods) {
            List<String> dupeModDeps = checkIfThereAreDepsFor(dupeMod.modpackMod, Path.of("./mods")); // TODO replace this with standardized mods path
            dupeModDepsList.addAll(dupeModDeps);
        }

        for (MOD_TO_MOD dupeMod : dupeMods) {
            dupeModDepsList.remove(dupeMod.modpackMod.modID());
        }

        for (MOD_TO_MOD dupeMod : dupeMods) {

            List<String> dupeModDeps = checkIfThereAreDepsFor(dupeMod.modpackMod, Path.of("./mods")); // TODO replace this with standardized mods path
            for (String mod : dupeModDepsList) {
                dupeModDeps.remove(mod);
            }

            if (!dupeModDeps.isEmpty()) {
                LOGGER.info("Mod {} is required by other mods", dupeMod.modpackMod.modPath());
                // check if modpack mod has different hash if so, copy it to default mods folder, delete old one
                if (!CustomFileUtils.getHash(dupeMod.modpackMod.modPath(), "sha1").equals(CustomFileUtils.getHash(dupeMod.defaultMod.modPath(), "sha1"))) {
                    LOGGER.info("Copying mod {} to default mods folder", dupeMod.modpackMod.modPath());
                    CustomFileUtils.copyFile(dupeMod.modpackMod.modPath(), dupeMod.defaultMod.modPath().getParent().resolve(dupeMod.modpackMod.modPath().getFileName()));
                    // and delete old one if the path is different
                    if (!dupeMod.modpackMod.modPath().equals(dupeMod.defaultMod.modPath())) {
                        LOGGER.info("Removing mod {}", dupeMod.defaultMod.modPath());
                        CustomFileUtils.forceDelete(dupeMod.defaultMod.modPath());
                    }
                    changedAnyThing = true;
                }
                continue;
            }

            LOGGER.warn("Removing mod {}", dupeMod.defaultMod.modPath());
            CustomFileUtils.forceDelete(dupeMod.defaultMod.modPath());
            changedAnyThing = true;
        }

        LOGGER.info("Finishing removing duplicate mods from default mods folder");

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
