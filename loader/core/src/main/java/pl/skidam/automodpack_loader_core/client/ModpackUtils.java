package pl.skidam.automodpack_loader_core.client;

import org.jetbrains.annotations.NotNull;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.LegacyClientCacheUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.utils.LegacyClientCacheUtils.*;

public class ModpackUtils {

    // Modpack may require update even if there's no files to update, because some files may need to be deleted
    public record UpdateCheckResult(boolean requiresUpdate, Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate) {}

    // Fast and friendly method to check if the modpack is up to date without modifying anything on disk
    public static UpdateCheckResult isUpdate(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        if (serverModpackContent == null || serverModpackContent.list == null) {
            throw new IllegalArgumentException("Server modpack content list is null");
        }

        var optionalClientModpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);
        if (optionalClientModpackContentFile.isEmpty() || !Files.exists(optionalClientModpackContentFile.get())) {
            return new UpdateCheckResult(true, serverModpackContent.list);
        }

        Jsons.ModpackContentFields clientModpackContent = ConfigTools.loadModpackContent(optionalClientModpackContentFile.get());
        if (clientModpackContent == null) {
            return new UpdateCheckResult(true, serverModpackContent.list);
        }

        LOGGER.info("Verifying content against server list...");
        var start = System.currentTimeMillis();

        Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate = ConcurrentHashMap.newKeySet();

        // Group & Sort Server Files (Optimizes Disk Seek Pattern)
        // Grouping by parent folder ensures we process the disk sequentially (Dir A, then Dir B).
        // TreeMap ensures alphabetical order of directories (HDD friendly).
        Map<Path, List<Jsons.ModpackContentFields.ModpackContentItem>> itemsByDir =
                serverModpackContent.list.stream()
                        .collect(Collectors.groupingBy(
                                item -> SmartFileUtils.getPath(modpackDir, item.file).getParent(),
                                TreeMap::new,
                                Collectors.toList()
                        ));

        try (var cache = FileMetadataCache.open(hashCacheDBFile)) {

            // Process Directory by Directory
            for (Map.Entry<Path, List<Jsons.ModpackContentFields.ModpackContentItem>> entry : itemsByDir.entrySet()) {
                Path parentDir = entry.getKey();
                List<Jsons.ModpackContentFields.ModpackContentItem> itemsInDir = entry.getValue();

                // If directory is missing, all items in it are missing.
                if (!Files.exists(parentDir)) {
                    filesToUpdate.addAll(itemsInDir);
                    continue;
                }

                // Read all file attributes in this folder in ONE pass.
                // This map will hold "FileName" -> "Attributes"
                Map<String, BasicFileAttributes> diskFiles = new HashMap<>();

                try {
                    // walkFileTree with depth 1 is efficient on Windows (gets attributes for free within a single syscall)
                    Files.walkFileTree(parentDir, EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
                        @NotNull @Override
                        public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                            diskFiles.put(file.getFileName().toString(), attrs);
                            return FileVisitResult.CONTINUE;
                        }

                        @NotNull @Override
                        public FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exc) {
                            return FileVisitResult.CONTINUE; // Handle locked files or permission errors gracefully
                        }
                    });
                } catch (IOException e) {
                    LOGGER.warn("Failed to inspect directory: {}", parentDir, e);
                    filesToUpdate.addAll(itemsInDir);
                    continue;
                }

                // Check Individual Files in a given directory (Pure RAM logic, 0 IO)
                for (var serverItem : itemsInDir) {
                    String fileName = Paths.get(serverItem.file).getFileName().toString();
                    BasicFileAttributes diskAttrs = diskFiles.get(fileName);

                    if (diskAttrs == null) {
                        // File does not exist in the directory map
                        filesToUpdate.add(serverItem);
                    } else {
                        if (serverItem.editable) { // TODO check if this is enough of a check, what if user already had a file but there's provided the same by a new modpack version which wasn't in the modpack before?
                            LOGGER.debug("Skipping editable file hash check: {}", serverItem.file);
                            continue;
                        }

                        // Check Size first from already read attributes
                        if (diskAttrs.size() != Long.parseLong(serverItem.size)) {
                            filesToUpdate.add(serverItem);
                            continue;
                        }

                        // Finally, check Hash
                        // We pass 'diskAttrs' to the cache so it doesn't need to re-stat the file.
                        String hash = cache.getHashOrNullWithAttributes(parentDir.resolve(fileName), diskAttrs);

                        if (!serverItem.sha1.equalsIgnoreCase(hash)) {
                            filesToUpdate.add(serverItem);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error during update check", e);
            // Fail-safe: assume update needed if process crashes
            return new UpdateCheckResult(true, serverModpackContent.list);
        }

        if (!filesToUpdate.isEmpty()) {
            LOGGER.info("Modpack {} requires update! Took {} ms", modpackDir, System.currentTimeMillis() - start);
            return new UpdateCheckResult(true, filesToUpdate);
        }

        LOGGER.info("Checking for deleted files...");

        Set<String> serverFileSet = serverModpackContent.list.stream()
                .map(item -> item.file)
                .collect(Collectors.toSet());

        for (Jsons.ModpackContentFields.ModpackContentItem clientItem : clientModpackContent.list) {
            if (!serverFileSet.contains(clientItem.file)) {
                LOGGER.info("Found file marked for deletion: {}", clientItem.file);
                return new UpdateCheckResult(true, Set.of());
            }
        }

        LOGGER.info("Modpack {} is up to date! Took {} ms", modpackDir, System.currentTimeMillis() - start);
        return new UpdateCheckResult(false, Set.of());
    }

    // Scans for files missing from the store. If found in the CWD (and the hash matches), copies them to the store.
    public static void populateStoreFromCWD(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate, FileMetadataCache cache) {
        for (var entry : filesToUpdate) {
            Path storeFile = SmartFileUtils.getPath(storeDir, entry.sha1);

            if (Files.exists(storeFile)) {
                LOGGER.debug("File already exists in store: {}", entry.file);
                continue;
            }

            Path fileInCWD = SmartFileUtils.getPathFromCWD(entry.file);
            if (Files.isRegularFile(fileInCWD)) {
                String diskHash = cache.getHashOrNull(fileInCWD);
                if (diskHash.equalsIgnoreCase(entry.sha1)) {
                    LOGGER.info("Copying existing file from CWD to store: {}", entry.file);
                    try {
                        SmartFileUtils.copyFile(fileInCWD, storeFile);
                    } catch (IOException e) {
                        LOGGER.error("Failed to copy file from CWD to store: {}", entry.file, e);
                    }
                }
            }
        }
    }

    // Returns the set of files that are missing from the store.
    public static Set<Jsons.ModpackContentFields.ModpackContentItem> identifyUncachedFiles(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToCheck) {
        Set<Jsons.ModpackContentFields.ModpackContentItem> nonExistingFiles = new HashSet<>();
        for (var entry : filesToCheck) {
            Path storeFile = SmartFileUtils.getPath(storeDir, entry.sha1);

            if (!Files.exists(storeFile)) {
                nonExistingFiles.add(entry);
            }
        }
        return nonExistingFiles;
    }

    // Installs files from the store (storeDir/<sha1>) to the instance (modpackDir/<file>).
    // Attempts to hardlink first, falls back to a copy if that fails.
    public static void hardlinkModpack(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, FileMetadataCache cache) throws IOException {
        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String formattedFile = contentItem.file;
            Path modpackFile = SmartFileUtils.getPath(modpackDir, formattedFile);
            Path storeFile = SmartFileUtils.getPath(storeDir, contentItem.sha1);

            if (!Files.exists(storeFile)) {
                LOGGER.debug("File {} not found in store, can't hardlink", formattedFile);
                return;
            }

            if (!Files.exists(modpackFile)) {
                LOGGER.debug("Hard-linking {} file to the modpack directory", formattedFile);
                SmartFileUtils.hardlinkFile(storeFile, modpackFile);
            } else {
                String modpackFileHash = cache.getHashOrNull(modpackFile);
                if (!contentItem.sha1.equalsIgnoreCase(modpackFileHash)) {
                    LOGGER.debug("Over-hard-linking {} file in the modpack directory", formattedFile);
                    SmartFileUtils.hardlinkFile(storeFile, modpackFile);
                }
            }
        }
    }

    public static boolean deleteFilesMarkedForDeletionByTheServer(Set<Jsons.ModpackContentFields.FileToDelete> filesToDeleteOnClient, FileMetadataCache cache) {
        if (!clientConfig.allowRemoteNonModpackDeletions) {
            if (!filesToDeleteOnClient.isEmpty()) {
                LOGGER.warn("Server requested deletion of {} files, but remote deletions are disabled in client config! Consider deleting them manually.", filesToDeleteOnClient.size());
                for (var entry : filesToDeleteOnClient) {
                    LOGGER.warn("File marked for deletion: {} (sha1: {})", entry.file, entry.sha1);
                }
            }
            return false;
        }

        AtomicBoolean deletedAnyModFile = new AtomicBoolean(false);
        for (var entry : filesToDeleteOnClient) {
            if (wasThisTimestampEvaluatedBefore(entry.timestamp)) {
                LOGGER.info("Skipping deletion of {} - already evaluated", entry.file);
                continue;
            }

            String filePath = entry.file;
            String expectedHash = entry.sha1;


            // If the matching file path exists, and it is in fact a file, target it directly
            Path fileInCWD = SmartFileUtils.getPathFromCWD(filePath);
            if (Files.isRegularFile(fileInCWD)) {
                LOGGER.info("Found exact file to delete: {}", filePath);
                String diskHash = cache.getHashOrNull(fileInCWD);
                if (diskHash.equalsIgnoreCase(expectedHash)) {
                    boolean isModFile = FileInspection.isMod(fileInCWD);
                    LOGGER.warn("Deleting file marked for deletion by server: {}", filePath);
                    SmartFileUtils.executeOrder66(fileInCWD);
                    if (isModFile) {
                        deletedAnyModFile.set(true);
                    }
                }
            } else { // Otherwise, search the (parent) directory for matching files
                Path parentDir;
                if (Files.isDirectory(fileInCWD)) {
                    parentDir = fileInCWD;
                } else {
                    parentDir = fileInCWD.getParent();
                }

                LOGGER.info("Searching directory {} for files to delete matching: {}", parentDir, filePath);

                try (var stream = Files.list(parentDir)) {
                    stream.forEach(path -> {
                        if (!Files.isRegularFile(path)) return;
                        String diskHash = cache.getHashOrNull(path);
                        if (diskHash.equalsIgnoreCase(expectedHash)) {
                            boolean isModFile = FileInspection.isMod(path);
                            LOGGER.warn("Deleting file marked for deletion by server: {}", path);
                            SmartFileUtils.executeOrder66(path);
                            if (isModFile) {
                                deletedAnyModFile.set(true);
                            }
                        }
                    });
                } catch (Exception e) {
                    LOGGER.error("Error while searching for files to delete in directory: {}", parentDir, e);
                }
            }
        }

        for (var entry : filesToDeleteOnClient) {
            markTimestampAsEvaluated(entry.timestamp);
        }

        saveDeletedFilesTimestamps();

        return deletedAnyModFile.get();
    }

    public static boolean correctFilesLocations(Path modpackDir, Jsons.ModpackContentFields serverModpackContent, Set<String> filesNotToCopy, FileMetadataCache cache) throws IOException {
        boolean needsRestart = false;

        // correct the files locations
        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String formattedFile = contentItem.file;
            Path modpackFile = SmartFileUtils.getPath(modpackDir, formattedFile);
            Path runFile = SmartFileUtils.getPathFromCWD(formattedFile);
            boolean isMod = "mod".equals(contentItem.type);

            if (isMod) { // Make it into standardized mods directory, for support custom launchers
                runFile = SmartFileUtils.getPath(MODS_DIR, formattedFile.replaceFirst("/mods/", ""));
            }

            boolean modpackFileExists = Files.exists(modpackFile);
            boolean runFileExists = Files.exists(runFile);
            boolean runFileHashMatch = false;
            if (runFileExists) runFileHashMatch = Objects.equals(contentItem.sha1, cache.getHashOrNull(runFile));

            // We only copy mods to the run directory which are not ignored - which need a workaround
            // If its any other file type, always copy
            if (filesNotToCopy.contains(formattedFile)) {
                continue;
            }

            if (modpackFileExists && !runFileExists) {
                SmartFileUtils.copyFile(modpackFile, runFile);

                if (isMod) {
                    needsRestart = true;
                    LOGGER.warn("Applying workaround for {} mod", formattedFile);
                }
            } else if (!modpackFileExists) { // This should never happen, since we previously verified that whole modpack is downloaded
                LOGGER.error("File {} doesn't exist!? If you see this please report this to the automodpack repo and attach this log https://github.com/Skidamek/AutoModpack/issues", formattedFile);
                Thread.dumpStack();
            } else if (!runFileHashMatch) {
                SmartFileUtils.copyFile(modpackFile, runFile);
                if (isMod) {
                    needsRestart = true;
                    LOGGER.warn("Overwriting mod {} file to modpack version", formattedFile);
                } else {
                    LOGGER.info("Overwriting {} file to the modpack version", formattedFile);
                }
            }
        }

        return needsRestart;
    }

    public static boolean removeRestModsNotToCopy(Jsons.ModpackContentFields serverModpackContent, Set<String> filesNotToCopy, Set<Path> modsToKeep, FileMetadataCache cache) {
        boolean needsRestart = false;

        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String formattedFile = contentItem.file;
            Path runFile = SmartFileUtils.getPathFromCWD(formattedFile);
            boolean isMod = "mod".equals(contentItem.type);

            if (isMod) { // Make it into standardized mods directory, for support custom launchers
                runFile = SmartFileUtils.getPath(MODS_DIR, formattedFile.replaceFirst("/mods/", ""));
            }

            if (modsToKeep.contains(runFile)) {
                LOGGER.info("Keeping {} file in the standard mods directory", formattedFile);
                continue;
            }

            boolean runFileExists = Files.exists(runFile);
            boolean runFileHashMatch = false;
            if (runFileExists) runFileHashMatch = contentItem.sha1.equalsIgnoreCase(cache.getHashOrNull(runFile));

            if (runFileHashMatch && isMod && filesNotToCopy.contains(formattedFile)) {
                LOGGER.info("Deleting {} file from standard mods directory", formattedFile);
                SmartFileUtils.executeOrder66(runFile);
                needsRestart = true;
            }
        }

        return needsRestart;
    }

    // Copies necessary nested mods from modpack mods to standard mods folder
    // Returns true if requires client restart
    public static boolean fixNestedMods(List<FileInspection.Mod> conflictingNestedMods, Collection<FileInspection.Mod> standardModList, FileMetadataCache cache, ModFileCache modCache) throws IOException {
        if (conflictingNestedMods.isEmpty())
            return false;

        final List<String> standardModIDs = standardModList.stream().flatMap(mod -> mod.IDs().stream()).toList();
        boolean needsRestart = false;

        for (FileInspection.Mod mod : conflictingNestedMods) {
            // Check mods provides, if there's some mod which is named with the same id as some other mod 'provides' remove the mod which provides that id as well, otherwise loader will crash
            if (standardModIDs.stream().anyMatch(mod.IDs()::contains))
                continue;

            Path modPath = mod.path();
            Path standardModPath = MODS_DIR.resolve(modPath.getFileName());
            if (!Files.exists(standardModPath) || !mod.hash().equalsIgnoreCase(cache.getHashOrNull(standardModPath))) {
                needsRestart = true;
                LOGGER.info("Copying nested mod {} to standard mods folder", standardModPath.getFileName());
                SmartFileUtils.copyFile(modPath, standardModPath);
                var newMod = modCache.getModOrNull(standardModPath, cache);
                if (newMod != null) standardModList.add(newMod); // important
            }
        }

        return needsRestart;
    }

    // Returns ignored files list, which is conflicting nested mods + workarounds set
    public static Set<String> getIgnoredFiles(List<FileInspection.Mod> conflictingNestedMods, Set<String> workarounds) {
        Set<String> newIgnoredFiles = new HashSet<>(workarounds);

        for (FileInspection.Mod mod : conflictingNestedMods) {
            newIgnoredFiles.add(SmartFileUtils.formatPath(mod.path(), modpacksDir));
        }

        return newIgnoredFiles;
    }

    // Checks if in standard mods folder are any mods that are in modpack
    // Returns map of modpack mods and standard mods that have the same mod id they dont necessarily have to be the same*
    public static Map<FileInspection.Mod, FileInspection.Mod> getDupeMods(Path modpackDir, Set<String> ignoredMods, Collection<FileInspection.Mod> standardModList, Collection<FileInspection.Mod> modpackModList, Set<String> forceCopyFiles) {
        final Map<FileInspection.Mod, FileInspection.Mod> duplicates = new HashMap<>();

        for (FileInspection.Mod modpackMod : modpackModList) {
            FileInspection.Mod standardMod = standardModList.stream().filter(mod -> mod.IDs().stream().anyMatch(modpackMod.IDs()::contains)).findFirst().orElse(null);
            if (standardMod != null) {
                String formattedFile = SmartFileUtils.formatPath(modpackMod.path(), modpackDir);
                if (ignoredMods.contains(formattedFile) || forceCopyFiles.contains(formattedFile))
                    continue;

                duplicates.put(modpackMod, standardMod);
            }
        }

        return duplicates;
    }

    public record RemoveDupeModsResult(boolean requiresRestart, Set<Path> modsToKeep) {}

    // Returns true if removed any mod from standard mods folder
    // If the client mod is a duplicate of what modpack contains then it removes it from client so that you dont need to restart game just when you launched it and modpack get updated - basically having these mods separately allows for seamless updates
    // If you have client mods which require specific mod which is also a duplicate of what modpack contains it should stay
    public static RemoveDupeModsResult removeDupeMods(Path modpackDir, Collection<FileInspection.Mod> standardModList, Collection<FileInspection.Mod> modpackModList, Set<String> ignoredMods, Set<String> workaroundMods, Set<String> forceCopyFiles) throws IOException {
        var dupeMods = ModpackUtils.getDupeMods(modpackDir, ignoredMods, standardModList, modpackModList, forceCopyFiles);

        if (dupeMods.isEmpty()) {
            return new RemoveDupeModsResult(false, Set.of());
        }

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
        modsToKeep.forEach(mod -> idsToKeep.addAll(mod.IDs()));

        boolean requiresRestart = false;
        Set<Path> dependentMods = new HashSet<>();

        // Remove dupe mods unless they need to stay - workaround mods
        for (var dupeMod : dupeMods.entrySet()) {
            FileInspection.Mod modpackMod = dupeMod.getKey();
            FileInspection.Mod standardMod = dupeMod.getValue();
            Path modpackModPath = modpackMod.path();
            Path standardModPath = standardMod.path();
            String formatedPath = SmartFileUtils.formatPath(standardModPath, MODS_DIR.getParent());
            List<String> IDs = new ArrayList<>(modpackMod.IDs());
            String modId = IDs.get(0);

            boolean isDependent = IDs.stream().anyMatch(idsToKeep::contains);
            boolean isWorkaround = workaroundMods.contains(formatedPath);
            boolean isForceCopy = forceCopyFiles.contains(formatedPath);

            if (isDependent) {
                Path newStandardModPath = standardModPath.getParent().resolve(modpackModPath.getFileName());
                dependentMods.add(newStandardModPath);

                // Check if hashes are the same, if not remove the mod and copy the modpack mod from modpack to make sure we achieve parity,
                // If we break mod compat there that's up to the user to fix it, because they added their own mods, we need to guarantee that server modpack is working.
                if (!Objects.equals(modpackMod.hash(), standardMod.hash())) {
                    LOGGER.warn("Changing duplicated mod {} - {} to modpack version - {}", modId, standardMod.version(), modpackMod.version());
                    SmartFileUtils.executeOrder66(standardModPath, false);
                    SmartFileUtils.copyFile(modpackModPath, newStandardModPath); // TODO make sure we dont copy an empty invalid file there
                    requiresRestart = true;
                }
            } else if (!isWorkaround && !isForceCopy) {
                LOGGER.warn("Removing {} mod. It is duplicated modpack mod and no other mods are dependent on it!", modId);
                SmartFileUtils.executeOrder66(standardModPath, false);
                requiresRestart = true;
            }
        }

        LegacyClientCacheUtils.saveDummyFiles();

        return new RemoveDupeModsResult(requiresRestart, dependentMods);
    }

    private static void addDependenciesRecursively(FileInspection.Mod mod, Collection<FileInspection.Mod> modList, Set<FileInspection.Mod> modsToKeep) {
        for (String depId : mod.deps()) {
            for (FileInspection.Mod modItem : modList) {
                if (modItem.IDs().stream().anyMatch(s -> s.equalsIgnoreCase(depId)) && modsToKeep.add(modItem)) {
                    addDependenciesRecursively(modItem, modList, modsToKeep);
                }
            }
        }
    }

    public static Path renameModpackDir(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
        String currentName = clientConfig.selectedModpack;
        String newName = serverModpackContent.modpackName;

        if (clientConfig.installedModpacks == null || clientConfig.selectedModpack == null || clientConfig.selectedModpack.isBlank()) {
            return modpackDir;
        }

        if (newName.isEmpty() || newName.equals(currentName)) {
            return modpackDir;
        }

        var installedAddresses = clientConfig.installedModpacks.get(currentName);
        if (installedAddresses == null) {
            return modpackDir;
        }

        Path newModpackDir = modpackDir.getParent().resolve(newName);

        try {
            LOGGER.info("Renaming modpack directory: {} -> {}", modpackDir.getFileName(), newName);
            Files.move(modpackDir, newModpackDir, StandardCopyOption.REPLACE_EXISTING);

            removeModpackFromList(currentName);
            selectModpack(newModpackDir, installedAddresses, Set.of());

            LOGGER.info("Successfully renamed and reselected modpack: {}", newName);
            return newModpackDir;
        } catch (DirectoryNotEmptyException ignored) {
            LOGGER.warn("Could not rename: Target directory {} not empty", newName);
        } catch (IOException e) {
            LOGGER.error("Failed to rename modpack directory", e);
        }

        return modpackDir;
    }

    // Returns true if value changed
    public static boolean selectModpack(Path modpackDirToSelect, Jsons.ModpackAddresses modpackAddresses, Set<String> newDownloadedFiles) {
        String newName = modpackDirToSelect.getFileName().toString();
        String oldName = clientConfig.selectedModpack;

        // If nothing changed, update list only and return early to avoid I/O.
        if (Objects.equals(newName, oldName)) {
            ModpackUtils.addModpackToList(newName, modpackAddresses);
            return false;
        }

        LOGGER.info("Preserving editable files from old modpack and copying to new modpack...");

        // Preserve files from the OLD modpack (if it existed)
        if (oldName != null && !oldName.isBlank()) {
            processEditableFiles(modpacksDir.resolve(oldName), (dir, files) ->
                    ModpackUtils.preserveEditableFiles(dir, files, newDownloadedFiles));
        }

        // Restore/Copy files to the NEW modpack
        processEditableFiles(modpackDirToSelect, (dir, files) ->
                ModpackUtils.copyPreviousEditableFiles(dir, files, newDownloadedFiles));

        // Update Configuration and Save
        clientConfig.selectedModpack = newName;
        ConfigTools.save(clientConfigFile, clientConfig);
        ModpackUtils.addModpackToList(newName, modpackAddresses);

        LOGGER.info("Selected modpack: {}", newName);

        return true;
    }

    private static void processEditableFiles(Path modpackDir, java.util.function.BiConsumer<Path, Set<String>> action) {
        Path contentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
        Jsons.ModpackContentFields content = ConfigTools.loadModpackContent(contentFile);

        if (content != null) {
            Set<String> editableFiles = getEditableFiles(content.list);
            action.accept(modpackDir, editableFiles);
        }
    }

    public static void removeModpackFromList(String modpackName) {
        if (modpackName == null || modpackName.isEmpty()) {
            return;
        }

        if (clientConfig.installedModpacks != null && clientConfig.installedModpacks.containsKey(modpackName)) {
            Map<String, Jsons.ModpackAddresses> modpacks = new HashMap<>(clientConfig.installedModpacks);
            modpacks.remove(modpackName);
            clientConfig.installedModpacks = modpacks;
            ConfigTools.save(clientConfigFile, clientConfig);
        }
    }

    public static void addModpackToList(String modpackName, Jsons.ModpackAddresses modpackAddresses) {
        if (modpackName == null || modpackName.isEmpty() || modpackAddresses.isAnyEmpty()) {
            return;
        }

        Map<String, Jsons.ModpackAddresses> modpacks = new HashMap<>(clientConfig.installedModpacks);
        modpacks.put(modpackName, modpackAddresses);
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

        Path modpackDir = SmartFileUtils.getPath(modpacksDir, correctedName);

        if (!modpackName.isEmpty()) {
            String nameFromName = modpackName;

            if (FileInspection.isInValidFileName(modpackName)) {
                nameFromName = FileInspection.fixFileName(modpackName);
            }

            modpackDir = SmartFileUtils.getPath(modpacksDir, nameFromName);
        }

        return modpackDir;
    }

    public static Optional<Jsons.ModpackContentFields> requestServerModpackContent(Jsons.ModpackAddresses modpackAddresses, Secrets.Secret secret, boolean allowAskingUser) {
        return fetchModpackContent(modpackAddresses, secret,
                (client) -> client.downloadFile(new byte[0], modpackContentTempFile, null),
                "Fetched", allowAskingUser);
    }

    public static Optional<Jsons.ModpackContentFields> refreshServerModpackContent(Jsons.ModpackAddresses modpackAddresses, Secrets.Secret secret, byte[][] fileHashes, boolean allowAskingUser) {
        return fetchModpackContent(modpackAddresses, secret,
                (client) -> client.requestRefresh(fileHashes, modpackContentTempFile),
                "Re-fetched", allowAskingUser);
    }

    private static Optional<Jsons.ModpackContentFields> fetchModpackContent(Jsons.ModpackAddresses modpackAddresses, Secrets.Secret secret, Function<DownloadClient, Future<Path>> operation, String fetchType, boolean allowAskingUser) {
        if (secret == null)
            return Optional.empty();
        if (modpackAddresses.isAnyEmpty())
            throw new IllegalArgumentException("Modpack addresses are empty!");

        try (DownloadClient client = DownloadClient.tryCreate(modpackAddresses, secret.secretBytes(), 1, userValidationCallback(modpackAddresses.hostAddress, allowAskingUser))) {
            if (client == null) return Optional.empty();
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
        }

        return Optional.empty();
    }

    public static boolean canConnectModpackHost(Jsons.ModpackAddresses modpackAddresses) {
        if (modpackAddresses.isAnyEmpty())
            throw new IllegalArgumentException("Modpack addresses are empty!");

        try (DownloadClient client = DownloadClient.tryCreate(modpackAddresses, null, 1, null)) {
            return client != null;
        } catch (Exception e) {
            LOGGER.error("Error while pinging AutoModpack host server", e);
        }

        return false;
    }

    /**
     * Returns a callback for use with {@link DownloadClient} that checks for trusted fingerprints in the known hosts
     * list of the client config.
     *
     * @param address         the address being connected to
     * @param allowAskingUser whether the user should be prompted if a certificate is not trusted
     * @return the callback
     */
    public static Function<X509Certificate, Boolean> userValidationCallback(InetSocketAddress address, boolean allowAskingUser) {
        return certificate -> {
            String fingerprint;
            try {
                fingerprint = NetUtils.getFingerprint(certificate);
            } catch (CertificateEncodingException e) {
                return false;
            }
            if (Objects.equals(knownHosts.hosts.get(address.getHostString()), fingerprint))
                return true;
            LOGGER.warn("Received untrusted certificate from server {}!", address.getHostString());
            if (allowAskingUser) {
                boolean trusted = askUserAboutCertificate(address, fingerprint);
                if (trusted) {
                    knownHosts.hosts.put(address.getHostString(), fingerprint);
                    ConfigTools.save(knownHostsFile, knownHosts);
                }
                return trusted;
            }

            return false;
        };
    }

    private static Boolean askUserAboutCertificate(InetSocketAddress address, String fingerprint) {
        LOGGER.info("Asking user for {}", address.getHostString());
        Optional<Object> screen = new ScreenManager().getScreen();
        if (screen.isEmpty()) {
            LOGGER.warn("No screen available, cannot ask user");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);

        AtomicBoolean accepted = new AtomicBoolean(false);
        Runnable trustCallback = () -> {
            accepted.set(true);
            latch.countDown();
        };
        Runnable cancelCallback = latch::countDown;
        new ScreenManager().validation(screen.get(), fingerprint, trustCallback, cancelCallback);
        try {
            latch.await();
        } catch (InterruptedException e) {
            return false;
        }

        return accepted.get();
    }

    public static boolean potentiallyMalicious(Jsons.ModpackContentFields serverModpackContent) {
        if (isUnsafePath(serverModpackContent.modpackName, true)) {
            LOGGER.error("Modpack content is invalid: modpack name '{}' is unsafe/malicious", serverModpackContent.modpackName);
            return true;
        }

        if (serverModpackContent.list == null || serverModpackContent.list.isEmpty()) {
            return false;
        }

        boolean listInvalid = serverModpackContent.list.stream().anyMatch(item -> {
            if (isHashInvalid(item.sha1)) {
                LOGGER.error("Modpack content is invalid: file '{}' has invalid sha1 '{}'", item.file, item.sha1);
                return true;
            }
            if (isUnsafePath(item.file, false)) {
                LOGGER.error("Modpack content is invalid: file path '{}' is unsafe/malicious", item.file);
                return true;
            }
            return false;
        });

        boolean nonModpackFilesToDeleteInvalid = serverModpackContent.nonModpackFilesToDelete.stream().anyMatch(item -> {
            if (isHashInvalid(item.sha1)) {
                LOGGER.error("Modpack content is invalid: file '{}' has invalid sha1 '{}'", item.file, item.sha1);
                return true;
            }
            if (isUnsafePath(item.file, false)) {
                LOGGER.error("Modpack content is invalid: file to delete path '{}' is unsafe/malicious", item.file);
                return true;
            }
            return false;
        });

        return listInvalid || nonModpackFilesToDeleteInvalid;
    }

    // Assumes sha1 hash
    private static boolean isHashInvalid(String hash) {
        if (hash == null || hash.isBlank()) {
            return true;
        }

        // SHA-1 hashes are 40 hexadecimal characters
        return !hash.matches("^[a-fA-F0-9]{40}$");
    }

    private static boolean isUnsafePath(String rawPath, boolean blankIsFine) {
        if (rawPath == null) return true;

        if (!blankIsFine && rawPath.isBlank()) return true;

        // Null Byte Check
        if (rawPath.indexOf('\0') != -1) return true;

        // Most files are just "mods/fabric-api.jar", so they hit this and return false immediately
        if (!rawPath.contains("..")) {
            return false;
        }

        // We must distinguish between malicious "../" and valid names like "super..mario.jar"
        String normalized = rawPath.replace('\\', '/');

        // Edge case
        if (normalized.equals("..") || normalized.equals(".")) return true;

        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (segment.equals("..")) return true; // Directory traversal
        }

        if (normalized.startsWith("automodpack/") || normalized.startsWith("/automodpack/")) {
            return true; // Trying to mess with automodpack internal files
        }

        return false;
    }

    public static void preserveEditableFiles(Path modpackDir, Set<String> editableFiles, Set<String> newDownloadedFiles) {
        for (String file : editableFiles) {
            if (newDownloadedFiles.contains(file)) // Don't mess with new downloaded files here
                continue;

            // Here, mods can be copied, no problem

            Path path = SmartFileUtils.getPathFromCWD(file);
            if (Files.exists(path)) {
                try {
                    SmartFileUtils.copyFile(path, SmartFileUtils.getPath(modpackDir, file));
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

            Path path = SmartFileUtils.getPath(modpackDir, file);
            if (Files.exists(path)) {
                try {
                    SmartFileUtils.copyFile(path, SmartFileUtils.getPathFromCWD(file));
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
