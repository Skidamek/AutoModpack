package pl.skidam.automodpack_loader_core.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.Url;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

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

            Jsons.ModpackContentFields clientModpackContent = ConfigTools.loadModpackContent(optionalClientModpackContentFile.get());

            if (clientModpackContent == null) {
                return true;
            }

            LOGGER.info("Checking files...");
            for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : serverModpackContent.list) {
                String file = modpackContentField.file;
                String serverSHA1 = modpackContentField.sha1;

                Path path = Path.of(modpackDir + file);

                if (Files.exists(path)) {
                    if (modpackContentField.editable) continue;
                } else {
                    Path standardPath = Path.of("." + file);
                    if (Files.exists(standardPath) && Objects.equals(serverSHA1, CustomFileUtils.getHash(standardPath, "sha1").orElse(null))) {
                        LOGGER.info("File {} already exists on client, coping to modpack", standardPath.getFileName());
                        try { CustomFileUtils.copyFile(standardPath, path); } catch (IOException e) { e.printStackTrace(); }
                        continue;
                    } else {
                        LOGGER.info("File does not exists {}", standardPath);
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

    public static boolean correctFilesLocations(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, Set<String> ignoreFiles) throws IOException {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return false;
        }

        boolean needsRestart = false;

        // correct the files locations
        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String formattedFile = contentItem.file;

            if (ignoreFiles.contains(formattedFile)) continue;

            Path modpackFile = Path.of(modpackDir + formattedFile).toAbsolutePath().normalize();
            Path runFile = Path.of("." + formattedFile).toAbsolutePath().normalize();

            if (contentItem.type.equals("mod")) {
                // Make it into standardized mods directory, for support custom launchers
                runFile = MODS_DIR.resolve(formattedFile.replaceFirst("/mods/", ""));
            }

            boolean modpackFileExists = Files.exists(modpackFile);
            boolean runFileExists = Files.exists(runFile);

            boolean needsReCheck = true;

            if (modpackFileExists && !runFileExists) {
                // We only copy mods which are not ignored -- which need a workaround
                if (contentItem.type.equals("mod")) {
                    needsRestart = true;
                    LOGGER.info("Applying workaround for {} mod", formattedFile);
                }

                CustomFileUtils.copyFile(modpackFile, runFile);
            } else if (!modpackFileExists && runFileExists) {
                CustomFileUtils.copyFile(runFile, modpackFile);
                needsReCheck = false;
            } else if (!modpackFileExists) {
                LOGGER.error("File {} doesn't exist!? If you see this please report this to the automodpack repo and attach this log https://github.com/Skidamek/AutoModpack/issues", formattedFile);
                Thread.dumpStack();
            }

            // we need to update run file and we assume that modpack file is up to date
            if (needsReCheck && Files.exists(runFile) && !Objects.equals(contentItem.sha1, CustomFileUtils.getHash(runFile, "sha1").orElse(null))) {
                LOGGER.info("Overwriting {} file to the modpack version", formattedFile);
                CustomFileUtils.copyFile(modpackFile, runFile);
            }
        }

        return needsRestart;
    }

    // Check if modpack mods are dependent on any mod in the default mods folder and if so, then check if the that dependency version is lower than required/provided by modpack mod.
    // if so then force move that modpack mod to the default mods folder and delete the old one from the default mods folder.
    public static boolean correctModpackDepsOnDefaultDir(Path modpackDir) throws IOException {
        AtomicBoolean needsRestart = new AtomicBoolean(false);
        List<LoaderService.Mod> standardModList = Files.list(MODS_DIR)
                .map(LOADER_MANAGER::getMod)
                .filter(Objects::nonNull)
                .toList();
        List<LoaderService.Mod> modpackModList = Files.list(modpackDir.resolve("mods"))
                .map(LOADER_MANAGER::getMod)
                .filter(Objects::nonNull)
                .toList();

        for (LoaderService.Mod modpackMod : modpackModList) {
            for (String depId : modpackMod.dependencies()) {
                standardModList.stream()
                        .filter(standardMod -> standardMod.modID().equals(depId) || standardMod.providesIDs().contains(depId))
                        .filter(standardMod -> standardMod.modVersion().compareTo(modpackMod.modVersion()) < 0)
                        .forEach(standardMod -> {
                            LOGGER.info("Moving {} mod to the default mods folder because it is dependent on {} mod and the version is lower", modpackMod.modID(), standardMod.modID());
                            try {
                                CustomFileUtils.copyFile(modpackMod.modPath(), MODS_DIR.resolve(modpackMod.modPath().getFileName()));
                                CustomFileUtils.forceDelete(standardMod.modPath());
                                needsRestart.set(true);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
            }
        }

        return needsRestart.get();
    }

    // Checks if in standard mods folder are any mods that are in modpack
    // Returns map of modpack mods and standard mods that have the same mod id they dont necessarily have to be the same*
    public static Map<LoaderService.Mod, LoaderService.Mod> getDupeMods(Path modpackDir, Set<String> workaroundMods) throws IOException {
        // maybe also check subfolders...
        final List<Path> standardMods = Files.list(MODS_DIR).toList();
        final List<Path> modpackMods = Files.list(modpackDir.resolve("mods")).toList();
        final Collection<LoaderService.Mod> standardModList = standardMods.stream().map(modPath -> LOADER_MANAGER.getMod(modPath)).filter(Objects::nonNull).toList();
        final Collection<LoaderService.Mod> modpackModList = modpackMods.stream().map(modPath -> LOADER_MANAGER.getMod(modPath)).filter(Objects::nonNull).toList();

        if (standardModList.isEmpty() || modpackModList.isEmpty()) return Map.of();

        final Map<LoaderService.Mod, LoaderService.Mod> duplicates = new HashMap<>();

        for (LoaderService.Mod modpackMod : modpackModList) {
            LoaderService.Mod standardMod = standardModList.stream().filter(mod -> mod.modID().equals(modpackMod.modID())).findFirst().orElse(null); // There might be super rare edge case if client would have for some reason more than one mod with the same mod id
            if (standardMod != null) {
                String formattedFile = CustomFileUtils.formatPath(modpackMod.modPath(), modpackDir);
                if (workaroundMods.contains(formattedFile)) continue;
                duplicates.put(modpackMod, standardMod);
            }
        }

        return duplicates;
    }

    // Returns true if removed any mod from standard mods folder
    // If the client mod is a duplicate of what modpack contains then it removes it from client so that you dont need to restart game just when you launched it and modpack get updated - basically having these mods separately allows for seamless updates
    // If you have client mods which require specific mod which is also a duplicate of what modpack contains it should stay
    public static boolean removeDupeMods(Map<LoaderService.Mod, LoaderService.Mod> dupeMods) throws IOException {
        List<Path> standardMods = Files.list(MODS_DIR).toList();
        Collection<LoaderService.Mod> standardModList = standardMods.stream().map(modPath -> LOADER_MANAGER.getMod(modPath)).filter(Objects::nonNull).toList();

        if (standardModList.isEmpty()) return false;

        Set<LoaderService.Mod> modsToKeep = new HashSet<>();

        // Fill out the sets with mods that are not duplicates and their dependencies
        for (LoaderService.Mod standardMod : standardModList) {
            if (!dupeMods.containsValue(standardMod)) {
                modsToKeep.add(standardMod);
                addDependenciesRecursively(standardMod, standardModList, modsToKeep);
            }
        }

        // Mods may provide more IDs
        Set<String> idsToKeep = new HashSet<>();
        modsToKeep.forEach(mod -> {
            idsToKeep.add(mod.modID());
            idsToKeep.addAll(mod.providesIDs());
        });

        List<Path> deletedMods = new ArrayList<>();

        // Remove dupe mods
        for (var dupeMod : dupeMods.entrySet()) {
            LoaderService.Mod modpackMod = dupeMod.getKey();
            LoaderService.Mod standardMod = dupeMod.getValue();
            Path modpackModPath = modpackMod.modPath();
            Path standardModPath = standardMod.modPath();
            String modId = modpackMod.modID();
            Collection<String> providesIDs = modpackMod.providesIDs();
            List<String> IDs = new ArrayList<>(providesIDs);
            IDs.add(modId);

            boolean isDependent = IDs.stream().anyMatch(idsToKeep::contains);

            if (isDependent) {
                // Check if hashes are the same, if not remove the mod and copy the modpack mod from modpack to make sure we achieve parity,
                // If we break mod compat there that's up to the user to fix it, because they added their own mods, we need to guarantee that server modpack is working.
                String modpackModHash = CustomFileUtils.getHash(modpackModPath, "sha1").orElse(null);
                String standardModHash = CustomFileUtils.getHash(standardModPath, "sha1").orElse(null);
                if (!Objects.equals(modpackModHash, standardModHash)) {
                    LOGGER.warn("Changing duplicated mod {} - {} to modpack version - {}", modId, standardMod.modVersion(), modpackMod.modVersion());
                    CustomFileUtils.forceDelete(standardModPath);
                    CustomFileUtils.copyFile(modpackModPath, standardModPath.getParent().resolve(modpackModPath.getFileName()));
                    deletedMods.add(standardModPath);
                }
            } else {
                LOGGER.warn("Removing {} mod. It is duplicated modpack mod and no other mods are dependent on it!", modId);
                CustomFileUtils.forceDelete(standardModPath);
                deletedMods.add(standardModPath);
            }
        }

        return !deletedMods.isEmpty();
    }

    private static void addDependenciesRecursively(LoaderService.Mod mod, Collection<LoaderService.Mod> modList, Set<LoaderService.Mod> modsToKeep) {
        for (String depId : mod.dependencies()) {
            for (LoaderService.Mod modItem : modList) {
                if ((modItem.modID().equals(depId) || modItem.providesIDs().contains(depId)) && modsToKeep.add(modItem)) {
                    addDependenciesRecursively(modItem, modList, modsToKeep);
                }
            }
        }
    }

    public static Path renameModpackDir(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        if (clientConfig.installedModpacks == null || clientConfig.selectedModpack == null || clientConfig.selectedModpack.isBlank()) {
            return modpackDir;
        }

        String installedModpackName = clientConfig.selectedModpack;
        String installedModpackLink = clientConfig.installedModpacks.get(installedModpackName);
        String serverModpackName = serverModpackContent.modpackName;

        if (!serverModpackName.equals(installedModpackName) && !serverModpackName.isEmpty()) {

            Path newModpackDir = modpackDir.getParent().resolve(serverModpackName);

            try {
                Files.move(modpackDir, newModpackDir, StandardCopyOption.REPLACE_EXISTING);

                removeModpackFromList(installedModpackName);

                LOGGER.info("Changed modpack name of {} to {}", modpackDir.getFileName().toString(), serverModpackName);
            } catch (DirectoryNotEmptyException ignored) {
            } catch (IOException e) {
                e.printStackTrace();
            }

            selectModpack(newModpackDir, installedModpackLink);

            return newModpackDir;
        }

        return modpackDir;
    }

    // Returns true if value changed
    public static boolean selectModpack(Path modpackDirToSelect, String modpackLinkToSelect) {
        final String modpackToSelect = modpackDirToSelect.getFileName().toString();

        String selectedModpack = clientConfig.selectedModpack;
        String selectedModpackLink = clientConfig.installedModpacks.get(selectedModpack);

        // Save current editable files
        Path selectedModpackDir = modpacksDir.resolve(selectedModpack);
        Path selectedModpackContentFile = selectedModpackDir.resolve(hostModpackContentFile.getFileName());
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(selectedModpackContentFile);
        if (modpackContent != null) {
            Set<String> editableFiles = getEditableFiles(modpackContent.list);
            ModpackUtils.preserveEditableFiles(selectedModpackDir, editableFiles);
        }

        // Copy editable files from modpack to select
        Path modpackContentFile = modpackDirToSelect.resolve(hostModpackContentFile.getFileName());
        Jsons.ModpackContentFields modpackContentToSelect = ConfigTools.loadModpackContent(modpackContentFile);
        if (modpackContentToSelect != null) {
            Set<String> editableFiles = getEditableFiles(modpackContentToSelect.list);
            ModpackUtils.copyPreviousEditableFiles(modpackDirToSelect, editableFiles);
        }

        clientConfig.selectedModpack = modpackToSelect;
        ConfigTools.save(clientConfigFile, clientConfig);
        ModpackUtils.addModpackToList(modpackToSelect, modpackLinkToSelect);
        return !Objects.equals(modpackToSelect, selectedModpack) || !Objects.equals(modpackLinkToSelect, selectedModpackLink);
    }

    public static void removeModpackFromList(String modpackName) {
        if (modpackName == null || modpackName.isEmpty()) {
            return;
        }

        if (clientConfig.installedModpacks != null && clientConfig.installedModpacks.containsKey(modpackName)) {
            Map<String, String> modpacks = new HashMap<>(clientConfig.installedModpacks);
            modpacks.remove(modpackName);
            clientConfig.installedModpacks = modpacks;
            ConfigTools.save(clientConfigFile, clientConfig);
        }
    }

    public static void addModpackToList(String modpackName, String link) {
        if (modpackName == null || modpackName.isEmpty() || link == null || link.isEmpty()) {
            return;
        }

        Map<String, String> modpacks = new HashMap<>(clientConfig.installedModpacks);
        modpacks.put(modpackName, link);
        clientConfig.installedModpacks = modpacks;

        ConfigTools.save(clientConfigFile, clientConfig);
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
            if (clientConfig.installedModpacks != null && clientConfig.installedModpacks.containsValue(nameFromUrl)) {
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

            return connectionToModpack(connection);
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return Optional.empty();
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

            return connectionToModpack(connection, body);
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return Optional.empty();
    }

    public static Optional<Jsons.ModpackContentFields> connectionToModpack(HttpURLConnection connection) {
        return connectionToModpack(connection, null);
    }

    public static Optional<Jsons.ModpackContentFields> connectionToModpack(HttpURLConnection connection, String body) {
        int responseCode = -1;
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
            if (body != null) {
                connection.setDoOutput(true);
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            connection.connect();

            responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                return parseStreamToModpack(connection.getInputStream());
            } else {
                LOGGER.error("Couldn't connect to modpack server: {} Response Code: {}", connection.getURL(), responseCode);
            }

        } catch (SocketException | SocketTimeoutException e) {
            LOGGER.error("Couldn't connect to modpack server: {} Response Code: {} Error: {}", connection.getURL(), responseCode, e.getCause());
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content", e);
        }

        return Optional.empty();
    }

    public static Optional<Jsons.ModpackContentFields> parseStreamToModpack(InputStream stream) {

        String response = null;

        try (InputStreamReader isr = new InputStreamReader(stream)) {
            JsonElement element = new JsonParser().parse(isr); // Needed to parse by deprecated method because of older minecraft versions (<1.17.1)
            if (element != null && !element.isJsonArray()) {
                JsonObject obj = element.getAsJsonObject();
                response = obj.toString();
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't parse modpack content", e);
        }

        if (response == null) {
            LOGGER.error("Couldn't parse modpack content");
            return Optional.empty();
        }

        Jsons.ModpackContentFields serverModpackContent = GSON.fromJson(response, Jsons.ModpackContentFields.class);

        if (serverModpackContent == null) {
            LOGGER.error("Couldn't parse modpack content");
            return Optional.empty();
        }

        if (serverModpackContent.list.isEmpty()) {
            LOGGER.error("Modpack content is empty!");
            return Optional.empty();
        }

        if (potentiallyMalicious(serverModpackContent)) {
            return Optional.empty();
        }

        return Optional.of(serverModpackContent);
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

            String sha1 = modpackContentItem.sha1;
            if (sha1 == null || sha1.equals("null")) {
                LOGGER.error("Modpack content is invalid, it contains null sha1 in file of {}", file);
                return true;
            }
        }

        return false;
    }

    public static void preserveEditableFiles(Path modpackDir, Set<String> editableFiles) {
        for (String file : editableFiles) {
            Path path = Path.of("." + file);
            if (Files.exists(path)) {
                try {
                    CustomFileUtils.copyFile(path, Path.of(modpackDir + file));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void copyPreviousEditableFiles(Path modpackDir, Set<String> editableFiles) {
        for (String file : editableFiles) {
            Path path = Path.of(modpackDir + file);
            if (Files.exists(path)) {
                try {
                    CustomFileUtils.copyFile(path, Path.of("." + file));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    static Set<String> getEditableFiles(Set<Jsons.ModpackContentFields.ModpackContentItem> modpackContentItems) {
        Set<String> editableFiles = new HashSet<>();

        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentItem : modpackContentItems) {
            if (modpackContentItem.editable) {
                editableFiles.add(modpackContentItem.file);
            }
        }

        return editableFiles;
    }
}
