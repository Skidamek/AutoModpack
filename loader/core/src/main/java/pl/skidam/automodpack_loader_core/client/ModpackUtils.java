package pl.skidam.automodpack_loader_core.client;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
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
                    LOGGER.info("File does not exist {} - {}", standardPath, file);
                    return true;
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
        boolean needsRestart = false;

        // correct the files locations
        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String formattedFile = contentItem.file;
            Path modpackFile = CustomFileUtils.getPath(modpackDir, formattedFile);
            Path runFile = CustomFileUtils.getPathFromCWD(formattedFile);
            boolean isMod = "mod".equals(contentItem.type);

            if (isMod) { // Make it into standardized mods directory, for support custom launchers
                runFile = CustomFileUtils.getPath(MODS_DIR, formattedFile.replaceFirst("/mods/", ""));
            }

            boolean modpackFileExists = Files.exists(modpackFile);
            boolean runFileExists = Files.exists(runFile);
            boolean runFileHashMatch = Objects.equals(contentItem.sha1, CustomFileUtils.getHash(runFile));

            if (runFileHashMatch && !modpackFileExists) {
                LOGGER.debug("Copying {} file to the modpack directory", formattedFile);
                CustomFileUtils.copyFile(runFile, modpackFile);
                modpackFileExists = true;
            }

            // We only copy mods to the run directory which are not ignored - which need a workaround
            // If its any other file type, always copy
            if (filesNotToCopy.contains(formattedFile)) {
                continue;
            }

            if (modpackFileExists && !runFileExists) {
                CustomFileUtils.copyFile(modpackFile, runFile);

                if (isMod) {
                    needsRestart = true;
                    LOGGER.warn("Applying workaround for {} mod", formattedFile);
                }
            } else if (!modpackFileExists) { // This should never happen, since we previously verified that whole modpack is downloaded
                LOGGER.error("File {} doesn't exist!? If you see this please report this to the automodpack repo and attach this log https://github.com/Skidamek/AutoModpack/issues", formattedFile);
                Thread.dumpStack();
            } else if (!runFileHashMatch) {
                CustomFileUtils.copyFile(modpackFile, runFile);
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

    public static boolean removeRestModsNotToCopy(Jsons.ModpackContentFields serverModpackContent, Set<String> filesNotToCopy, Set<Path> modsToKeep) {
        boolean needsRestart = false;

        for (Jsons.ModpackContentFields.ModpackContentItem contentItem : serverModpackContent.list) {
            String formattedFile = contentItem.file;
            Path runFile = CustomFileUtils.getPathFromCWD(formattedFile);
            boolean isMod = "mod".equals(contentItem.type);

            if (isMod) { // Make it into standardized mods directory, for support custom launchers
                runFile = CustomFileUtils.getPath(MODS_DIR, formattedFile.replaceFirst("/mods/", ""));
            }

            if (modsToKeep.contains(runFile)) {
                LOGGER.info("Keeping {} file in the standard mods directory", formattedFile);
                continue;
            }

            boolean runFileExists = Files.exists(runFile);
            boolean runFileHashMatch = Objects.equals(contentItem.sha1, CustomFileUtils.getHash(runFile));

            if (runFileHashMatch && runFileExists && isMod && filesNotToCopy.contains(formattedFile)) {
                LOGGER.info("Deleting {} file from standard mods directory", formattedFile);
                CustomFileUtils.executeOrder66(runFile);
                needsRestart = true;
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

    // Returns ignored files list, which is conflicting nested mods + workarounds set
    public static Set<String> getIgnoredFiles(List<FileInspection.Mod> conflictingNestedMods, Set<String> workarounds) {
        Set<String> newIgnoredFiles = new HashSet<>(workarounds);

        for (FileInspection.Mod mod : conflictingNestedMods) {
            newIgnoredFiles.add(CustomFileUtils.formatPath(mod.modPath(), modpacksDir));
        }

        return newIgnoredFiles;
    }

    // Checks if in standard mods folder are any mods that are in modpack
    // Returns map of modpack mods and standard mods that have the same mod id they dont necessarily have to be the same*
    public static Map<FileInspection.Mod, FileInspection.Mod> getDupeMods(Path modpackDir, Set<String> ignoredMods, Collection<FileInspection.Mod> standardModList, Collection<FileInspection.Mod> modpackModList) {
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

    public record RemoveDupeModsResult(boolean requiresRestart, Set<Path> modsToKeep) {}

    // Returns true if removed any mod from standard mods folder
    // If the client mod is a duplicate of what modpack contains then it removes it from client so that you dont need to restart game just when you launched it and modpack get updated - basically having these mods separately allows for seamless updates
    // If you have client mods which require specific mod which is also a duplicate of what modpack contains it should stay
    public static RemoveDupeModsResult removeDupeMods(Path modpackDir, Collection<FileInspection.Mod> standardModList, Collection<FileInspection.Mod> modpackModList, Set<String> ignoredMods, Set<String> workaroundMods, Set<String> forceCopyFiles) throws IOException {
        var dupeMods = ModpackUtils.getDupeMods(modpackDir, ignoredMods, standardModList, modpackModList);

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
        modsToKeep.forEach(mod -> {
            idsToKeep.add(mod.modID());
            idsToKeep.addAll(mod.providesIDs());
        });

        boolean requiresRestart = false;
        Set<Path> dependentMods = new HashSet<>();

        // Remove dupe mods unless they need to stay - workaround mods
        for (var dupeMod : dupeMods.entrySet()) {
            FileInspection.Mod modpackMod = dupeMod.getKey();
            FileInspection.Mod standardMod = dupeMod.getValue();
            Path modpackModPath = modpackMod.modPath();
            Path standardModPath = standardMod.modPath();
            String modId = modpackMod.modID();
            String formatedPath = CustomFileUtils.formatPath(standardModPath, MODS_DIR.getParent());
            Collection<String> providesIDs = modpackMod.providesIDs();
            List<String> IDs = new ArrayList<>(providesIDs);
            IDs.add(modId);

            boolean isDependent = IDs.stream().anyMatch(idsToKeep::contains);
            boolean isWorkaround = workaroundMods.contains(formatedPath);
            boolean isForceCopy = forceCopyFiles.contains(formatedPath);

            if (isDependent) {
                Path newStandardModPath = standardModPath.getParent().resolve(modpackModPath.getFileName());
                dependentMods.add(newStandardModPath);

                // Check if hashes are the same, if not remove the mod and copy the modpack mod from modpack to make sure we achieve parity,
                // If we break mod compat there that's up to the user to fix it, because they added their own mods, we need to guarantee that server modpack is working.
                if (!Objects.equals(modpackMod.hash(), standardMod.hash())) {
                    LOGGER.warn("Changing duplicated mod {} - {} to modpack version - {}", modId, standardMod.modVersion(), modpackMod.modVersion());
                    CustomFileUtils.executeOrder66(standardModPath);
                    CustomFileUtils.copyFile(modpackModPath, newStandardModPath);
                    requiresRestart = true;
                }
            } else if (!isWorkaround && !isForceCopy) {
                LOGGER.warn("Removing {} mod. It is duplicated modpack mod and no other mods are dependent on it!", modId);
                CustomFileUtils.executeOrder66(standardModPath);
                requiresRestart = true;
            }
        }

        return new RemoveDupeModsResult(requiresRestart, dependentMods);
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
        Jsons.ModpackAddresses installedModpackAddresses = clientConfig.installedModpacks.get(installedModpackName);
        String serverModpackName = serverModpackContent.modpackName;

        if (installedModpackAddresses != null && !serverModpackName.equals(installedModpackName) && !serverModpackName.isEmpty()) {

            Path newModpackDir = modpackDir.getParent().resolve(serverModpackName);

            try {
                Files.move(modpackDir, newModpackDir, StandardCopyOption.REPLACE_EXISTING);

                removeModpackFromList(installedModpackName);

                LOGGER.info("Changed modpack name of {} to {}", modpackDir.getFileName().toString(), serverModpackName);
            } catch (DirectoryNotEmptyException ignored) {
            } catch (IOException e) {
                e.printStackTrace();
            }

            selectModpack(newModpackDir, installedModpackAddresses, Set.of());

            return newModpackDir;
        }

        return modpackDir;
    }
    //get minecraft path....
    public static Path getMinecraftPath() {
        return Path.of(System.getProperty("user.dir"));
    }

    //try to get modpacks about minecraft path for utils
    public static Path getModpackPathFolder(String modpackpackage) {
        return getMinecraftPath().resolve("automodpack/host-modpack/").resolve(modpackpackage);
    }
    // get all client Packages and paths from host-modpack util test
    public static Path getClientPackage() {
        return getMinecraftPath().resolve("automodpack/host-modpack");
    }

    //add FullserverPack to selecting packs and it is need to change save folder
    //add corrected Modpackpath if fullserverpack is selected
    public static Path getCorrectModpackDir(Path modpackDirToSelect) {
        if (modpackDirToSelect.getFileName().toString().equalsIgnoreCase("fullserver")) {
            return hostFullServerPackDir.resolve("fullserver");
        }
        return modpackDirToSelect;
    }

    // Returns true if value changed
    public static boolean selectModpack(Path modpackDirToSelect, InetSocketAddress modpackAddressToSelect, Set<String> newDownloadedFiles) {
        modpackDirToSelect = getCorrectModpackDir(modpackDirToSelect);

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
        ModpackUtils.addModpackToList(modpackToSelect, modpackAddresses);

        return !Objects.equals(modpackToSelect, selectedModpack);
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
        if (modpackName.equalsIgnoreCase("fullserver")) {
            return hostFullServerPackDir.resolve("fullserver");
        }

        String strAddress = address.getHostString() + ":" + address.getPort();
        String correctedName = strAddress;

        if (FileInspection.isInValidFileName(strAddress)) {
            correctedName = FileInspection.fixFileName(strAddress);
        }

        Path modpackDir = CustomFileUtils.getPath(modpacksDir, correctedName);

        if (!modpackName.isEmpty()) {
            String nameFromName = modpackName;

            if (FileInspection.isInValidFileName(modpackName)) {
                nameFromName = FileInspection.fixFileName(modpackName);
            }

            modpackDir = CustomFileUtils.getPath(modpacksDir, nameFromName);
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
        new ScreenManager().validation(screen.get(), fingerprint, trustCallback,
                cancelCallback);
        try {
            latch.await();
        } catch (InterruptedException e) {
            return false;
        }

        return accepted.get();
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
