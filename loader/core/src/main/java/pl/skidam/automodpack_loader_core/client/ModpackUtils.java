package pl.skidam.automodpack_loader_core.client;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Future;
import java.util.function.Function;

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

                Path path = CustomFileUtils.getPath(modpackDir, file);

                if (Files.exists(path)) {
                    if (modpackContentField.editable) continue;
                } else {
                    Path standardPath = CustomFileUtils.getPathFromCWD(file);
                    if (Files.exists(standardPath) && Objects.equals(serverSHA1, CustomFileUtils.getHash(standardPath))) {
                        LOGGER.info("File {} already exists on client, coping to modpack", file);
                        try { CustomFileUtils.copyFile(standardPath, path); } catch (IOException e) { e.printStackTrace(); }
                        continue;
                    } else {
                        LOGGER.info("File does not exists {} - {}", standardPath, file);
                        return true;
                    }
                }

                if (!Objects.equals(serverSHA1, CustomFileUtils.getHash(path))) {
                    LOGGER.info("File does not match hash {} - {}", path, file);
                    return true;
                }
            }

            // Server also might have deleted some files
            for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : clientModpackContent.list) {
                var serverItemOpt = serverModpackContent.list.stream().filter(item -> item.file.equals(modpackContentField.file)).findFirst();
                if (serverItemOpt.isEmpty()) {
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

    public static boolean correctFilesLocations(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, Set<String> filesNotToCopy) throws IOException {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return false;
        }

        boolean needsRestart = false;

        // correct the files locations
        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String formattedFile = contentItem.file;

            if (filesNotToCopy.contains(formattedFile)) continue;

            Path modpackFile = CustomFileUtils.getPath(modpackDir, formattedFile);
            Path runFile = CustomFileUtils.getPathFromCWD(formattedFile);

            if (contentItem.type.equals("mod")) {
                // Make it into standardized mods directory, for support custom launchers
                runFile = CustomFileUtils.getPath(MODS_DIR, formattedFile.replaceFirst("/mods/", ""));
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
            if (needsReCheck && Files.exists(runFile) && !Objects.equals(contentItem.sha1, CustomFileUtils.getHash(runFile))) {
                LOGGER.info("Overwriting {} file to the modpack version", formattedFile);
                CustomFileUtils.copyFile(modpackFile, runFile);
            }
        }

        return needsRestart;
    }

    // Copies necessary nested mods from modpack mods to standard mods folder
    // Returns true if requires client restart
    public static boolean fixNestedMods(List<FileInspection.Mod> conflictingNestedMods, Collection<FileInspection.Mod> standardModList) throws IOException {
        if (conflictingNestedMods.isEmpty())
            return false;

        final List<String> standardModIDs = standardModList.stream().map(FileInspection.Mod::modID).toList();
        boolean needsRestart = false;

        for (FileInspection.Mod mod : conflictingNestedMods) {
            // Check mods provides, if theres some mod which is named with the same id as some other mod 'provides' remove the mod which provides that id as well, otherwise loader will crash
            if (standardModIDs.stream().anyMatch(mod.providesIDs()::contains))
                continue;

            Path modPath = mod.modPath();
            Path standardModPath = MODS_DIR.resolve(modPath.getFileName());
            if (!Objects.equals(CustomFileUtils.getHash(standardModPath), mod.hash())) {
                needsRestart = true;
                LOGGER.info("Copying nested mod {} to standard mods folder", standardModPath.getFileName());
                CustomFileUtils.copyFile(modPath, standardModPath);
                var newMod = FileInspection.getMod(standardModPath);
                if (newMod != null) standardModList.add(newMod); // important
            }
        }

        return needsRestart;
    }

    // Returns new ignored files list, accounting for conflicting nested mods
    public static Set<String> getIgnoredWithNested(List<FileInspection.Mod> conflictingNestedMods, Set<String> ignoredFiles) {
        Set<String> newIgnoredFiles = new HashSet<>(ignoredFiles);

        for (FileInspection.Mod mod : conflictingNestedMods) {
            newIgnoredFiles.add(CustomFileUtils.formatPath(mod.modPath(), modpacksDir));
        }

        return newIgnoredFiles;
    }

    // Checks if in standard mods folder are any mods that are in modpack
    // Returns map of modpack mods and standard mods that have the same mod id they dont necessarily have to be the same*
    public static Map<FileInspection.Mod, FileInspection.Mod> getDupeMods(Path modpackDir, Set<String> ignoredMods, Collection<FileInspection.Mod> standardModList, Collection<FileInspection.Mod> modpackModList) {
        if (standardModList.isEmpty() || modpackModList.isEmpty()) return Map.of();

        final Map<FileInspection.Mod, FileInspection.Mod> duplicates = new HashMap<>();

        for (FileInspection.Mod modpackMod : modpackModList) {
            FileInspection.Mod standardMod = standardModList.stream().filter(mod -> mod.modID().equals(modpackMod.modID())).findFirst().orElse(null); // There might be super rare edge case if client would have for some reason more than one mod with the same mod id
            if (standardMod != null) {
                String formattedFile = CustomFileUtils.formatPath(modpackMod.modPath(), modpackDir);
                if (ignoredMods.contains(formattedFile))
                    continue;

                duplicates.put(modpackMod, standardMod);
            }
        }

        return duplicates;
    }

    // Returns true if removed any mod from standard mods folder
    // If the client mod is a duplicate of what modpack contains then it removes it from client so that you dont need to restart game just when you launched it and modpack get updated - basically having these mods separately allows for seamless updates
    // If you have client mods which require specific mod which is also a duplicate of what modpack contains it should stay
    public static boolean removeDupeMods(Path modpackDir, Collection<FileInspection.Mod> standardModList, Collection<FileInspection.Mod> modpackModList, Set<String> ignoredMods, Set<String> workaroundMods) throws IOException {

        var dupeMods = ModpackUtils.getDupeMods(modpackDir, ignoredMods, standardModList, modpackModList);

        if (dupeMods.isEmpty()) return false;

        Set<FileInspection.Mod> modsToKeep = new HashSet<>();

        // Fill out the sets with mods that are not duplicates and their dependencies
        for (FileInspection.Mod standardMod : standardModList) {
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

        // Remove dupe mods unless they need to stay - workaround mods
        for (var dupeMod : dupeMods.entrySet()) {
            FileInspection.Mod modpackMod = dupeMod.getKey();
            FileInspection.Mod standardMod = dupeMod.getValue();
            Path modpackModPath = modpackMod.modPath();
            Path standardModPath = standardMod.modPath();
            String modId = modpackMod.modID();
            Collection<String> providesIDs = modpackMod.providesIDs();
            List<String> IDs = new ArrayList<>(providesIDs);
            IDs.add(modId);

            boolean isDependent = IDs.stream().anyMatch(idsToKeep::contains);
            boolean isWorkaround = workaroundMods.contains(CustomFileUtils.formatPath(standardModPath, MODS_DIR.getParent()));

            if (isDependent) {
                // Check if hashes are the same, if not remove the mod and copy the modpack mod from modpack to make sure we achieve parity,
                // If we break mod compat there that's up to the user to fix it, because they added their own mods, we need to guarantee that server modpack is working.
                if (!Objects.equals(modpackMod.hash(), standardMod.hash())) {
                    LOGGER.warn("Changing duplicated mod {} - {} to modpack version - {}", modId, standardMod.modVersion(), modpackMod.modVersion());
                    CustomFileUtils.forceDelete(standardModPath);
                    CustomFileUtils.copyFile(modpackModPath, standardModPath.getParent().resolve(modpackModPath.getFileName()));
                    deletedMods.add(standardModPath);
                }
            } else if (!isWorkaround) {
                LOGGER.warn("Removing {} mod. It is duplicated modpack mod and no other mods are dependent on it!", modId);
                CustomFileUtils.forceDelete(standardModPath);
                deletedMods.add(standardModPath);
            }
        }

        return !deletedMods.isEmpty();
    }

    private static void addDependenciesRecursively(FileInspection.Mod mod, Collection<FileInspection.Mod> modList, Set<FileInspection.Mod> modsToKeep) {
        for (String depId : mod.dependencies()) {
            for (FileInspection.Mod modItem : modList) {
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
        InetSocketAddress installedModpackAddress = AddressHelpers.parse(installedModpackLink);
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

            selectModpack(newModpackDir, installedModpackAddress, Set.of());

            return newModpackDir;
        }

        return modpackDir;
    }

    // Returns true if value changed
    public static boolean selectModpack(Path modpackDirToSelect, InetSocketAddress modpackAddressToSelect, Set<String> newDownloadedFiles) {
        final String modpackToSelect = modpackDirToSelect.getFileName().toString();
        String selectedModpack = clientConfig.selectedModpack;

        if (selectedModpack != null && !selectedModpack.isBlank()) {
            // Save current editable files
            Path selectedModpackDir = modpacksDir.resolve(selectedModpack);
            Path selectedModpackContentFile = selectedModpackDir.resolve(hostModpackContentFile.getFileName());
            Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(selectedModpackContentFile);
            if (modpackContent != null) {
                Set<String> editableFiles = getEditableFiles(modpackContent.list);
                ModpackUtils.preserveEditableFiles(selectedModpackDir, editableFiles, newDownloadedFiles);
            }
        }

        // Copy editable files from modpack to select
        Path modpackContentFile = modpackDirToSelect.resolve(hostModpackContentFile.getFileName());
        Jsons.ModpackContentFields modpackContentToSelect = ConfigTools.loadModpackContent(modpackContentFile);
        if (modpackContentToSelect != null) {
            Set<String> editableFiles = getEditableFiles(modpackContentToSelect.list);
            ModpackUtils.copyPreviousEditableFiles(modpackDirToSelect, editableFiles, newDownloadedFiles);
        }

        clientConfig.selectedModpack = modpackToSelect;
        ConfigTools.save(clientConfigFile, clientConfig);
        ModpackUtils.addModpackToList(modpackToSelect, modpackAddressToSelect);

        return !Objects.equals(modpackToSelect, selectedModpack);
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

    public static void addModpackToList(String modpackName, InetSocketAddress address) {
        if (modpackName == null || modpackName.isEmpty() || address == null) {
            return;
        }

        Map<String, String> modpacks = new HashMap<>(clientConfig.installedModpacks);
        String addressString = address.getHostString() + ":" + address.getPort();
        modpacks.put(modpackName, addressString);
        clientConfig.installedModpacks = modpacks;

        ConfigTools.save(clientConfigFile, clientConfig);
    }

    // Returns modpack name formatted for path or url if server doesn't provide modpack name
    public static Path getModpackPath(InetSocketAddress address, String modpackName) {

        String strAddress = address.getHostString() + ":" + address.getPort();
        String correctedName = strAddress;

        if (FileInspection.isInValidFileName(strAddress)) {
            correctedName = FileInspection.fixFileName(strAddress);
        }

        Path modpackDir = CustomFileUtils.getPath(modpacksDir, correctedName);

        if (!modpackName.isEmpty()) {
            // Check if we don't have already installed modpack via this link
            if (clientConfig.installedModpacks != null && clientConfig.installedModpacks.containsValue(correctedName)) {
                return modpackDir;
            }

            String nameFromName = modpackName;

            if (FileInspection.isInValidFileName(modpackName)) {
                nameFromName = FileInspection.fixFileName(modpackName);
            }

            modpackDir = CustomFileUtils.getPath(modpacksDir, nameFromName);
        }

        return modpackDir;
    }

    public static Optional<Jsons.ModpackContentFields> requestServerModpackContent(InetSocketAddress address, Secrets.Secret secret) {
        return fetchModpackContent(address, secret,
                (client) -> client.downloadFile(new byte[0], modpackContentTempFile, null),
                "Fetched");
    }

    public static Optional<Jsons.ModpackContentFields> refreshServerModpackContent(InetSocketAddress address, Secrets.Secret secret, byte[][] fileHashes) {
        return fetchModpackContent(address, secret,
                (client) -> client.requestRefresh(fileHashes, modpackContentTempFile),
                "Re-fetched");
    }

    private static Optional<Jsons.ModpackContentFields> fetchModpackContent(InetSocketAddress address, Secrets.Secret secret, Function<DownloadClient, Future<Path>> operation, String fetchType) {
        if (secret == null)
            return Optional.empty();
        if (address == null)
            throw new IllegalArgumentException("Address is null");

        DownloadClient client = null;
        try {
            client = new DownloadClient(address, secret, 1);
            var future = operation.apply(client);
            Path path = future.get();
            var content = Optional.ofNullable(ConfigTools.loadModpackContent(path));
            Files.deleteIfExists(modpackContentTempFile);

            if (content.isPresent() && potentiallyMalicious(content.get())) {
                return Optional.empty();
            }

            return content;
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content", e);
        } finally {
            if (client != null)
                client.close();
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

            String sha1 = modpackContentItem.sha1;
            if (sha1 == null || sha1.equals("null")) {
                LOGGER.error("Modpack content is invalid, it contains null sha1 in file of {}", file);
                return true;
            }
        }

        return false;
    }

    public static void preserveEditableFiles(Path modpackDir, Set<String> editableFiles, Set<String> newDownloadedFiles) {
        for (String file : editableFiles) {
            if (newDownloadedFiles.contains(file)) // Don't mess with new downloaded files here
                continue;

            // Here, mods can be copied, no problem

            Path path = CustomFileUtils.getPathFromCWD(file);
            if (Files.exists(path)) {
                try {
                    CustomFileUtils.copyFile(path, CustomFileUtils.getPath(modpackDir, file));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void copyPreviousEditableFiles(Path modpackDir, Set<String> editableFiles, Set<String> newDownloadedFiles) {
        for (String file : editableFiles) {
            if (newDownloadedFiles.contains(file)) // Don't mess with new downloaded files here
                continue;

            if (file.contains("/mods/") && file.endsWith(".jar")) // Don't mess with mods here, it will cause issues
                continue;

            Path path = CustomFileUtils.getPath(modpackDir, file);
            if (Files.exists(path)) {
                try {
                    CustomFileUtils.copyFile(path, CustomFileUtils.getPathFromCWD(file));
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
