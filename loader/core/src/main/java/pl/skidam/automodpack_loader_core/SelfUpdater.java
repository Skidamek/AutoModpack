package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.LockFreeInputStream;
import pl.skidam.automodpack_core.utils.SemanticVersion;
import pl.skidam.automodpack_loader_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static pl.skidam.automodpack_core.Constants.*;

public class SelfUpdater {

    public static final String AUTOMODPACK_ID = "k68glP2e"; // AutoModpack modrinth id

    // Hardcoded floor: 4.0.0 Stable.
    // Logic: 4.0.0-beta1 < 4.0.0 Stable. This prevents downgrading to unsafe betas.
    private static final SemanticVersion MINIMUM_SAFE_VERSION = new SemanticVersion(4, 0, 0, "release", Integer.MAX_VALUE);

    public static boolean update() {
        return update(null);
    }

    public static boolean update(Jsons.ModpackContentFields serverModpackContent) {
        if (LOADER_MANAGER.isDevelopmentEnvironment()) return false;

        if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER && !serverConfig.selfUpdater) {
            LOGGER.info("AutoModpack self-updater is disabled in server config.");
            return false;
        }

        boolean gettingServerVersion = serverModpackContent != null && serverModpackContent.automodpackVersion != null && !serverModpackContent.automodpackVersion.isBlank();

        if (!gettingServerVersion && LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT && !clientConfig.selfUpdater) {
            LOGGER.info("AutoModpack self-updater is disabled in client config.");
            return false;
        }

        // Identify Current Version
        SemanticVersion currentVersion;
        try {
            currentVersion = SemanticVersion.parse(AM_VERSION);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Current installed AutoModpack version is corrupt/invalid: " + AM_VERSION);
            return false;
        }

        // Fetch Remote Info
        List<ModrinthAPI> modrinthAPIList = new ArrayList<>();

        if (gettingServerVersion) {
            if (serverModpackContent.automodpackVersion.equals(AM_VERSION)) {
                LOGGER.info("AutoModpack is up-to-date with server version: {}", serverModpackContent.automodpackVersion);
                return false;
            }

            if (!clientConfig.syncAutoModpackVersion) {
                LOGGER.warn("Version syncing disabled. Cannot sync to server version: {}", serverModpackContent.automodpackVersion);
                return false;
            }

            LOGGER.info("Syncing AutoModpack to server version: {}", serverModpackContent.automodpackVersion);
            modrinthAPIList.add(ModrinthAPI.getModSpecificVersion(AUTOMODPACK_ID, serverModpackContent.automodpackVersion, serverModpackContent.mcVersion));
        } else {
            LOGGER.info("Checking if AutoModpack is up-to-date...");
            modrinthAPIList = ModrinthAPI.getModInfosFromID(AUTOMODPACK_ID);
        }

        if (modrinthAPIList == null || modrinthAPIList.isEmpty()) {
            LOGGER.warn("Couldn't get version info from Modrinth API.");
            return false;
        }

        // Iterate & Validate
        for (ModrinthAPI automodpack : modrinthAPIList) {
            if (automodpack == null || automodpack.fileVersion() == null) continue;

            String rawRemoteVersion = automodpack.fileVersion();
            SemanticVersion remoteVersion;

            try {
                remoteVersion = SemanticVersion.parse(rawRemoteVersion);
            } catch (IllegalArgumentException e) {
                continue; // Skip malformed remote versions
            }

            // Exact Hash Match (Fastest check)
            if (automodpack.SHA1Hash().equals(HashUtils.getHash(THIS_MOD_JAR))) {
                LOGGER.info("Already on the target version (Hash match): {}", AM_VERSION);
                return false;
            }

            // Version Comparison
            int comparison = remoteVersion.compareTo(currentVersion);

            // If we are NOT forced to sync to server, and remote is older or equal
            if (!gettingServerVersion && comparison <= 0) {
                if (comparison == 0) {
                    LOGGER.info("No updates found. You are on the latest version: {}", rawRemoteVersion);
                } else {
                    LOGGER.info("Development check: Installed version {} is newer/different than release {}.", AM_VERSION, rawRemoteVersion);
                }
                // Since Modrinth lists are usually sorted latest-first, if the first is older, no newer exists.
                return false;
            }

            // Stable -> Beta Protection
            // If checking for updates (not syncing), do not update FROM Stable TO Beta.
            if (!gettingServerVersion && currentVersion.isStable() && !remoteVersion.isStable()) {
                LOGGER.info("Skipping update: You are on Stable ({}) and latest is Pre-release ({}).", AM_VERSION, rawRemoteVersion);
                continue; // Skip this beta, keep looking for a newer Stable version in the list
            }

            // Safety / Downgrade Check
            if (!validUpdate(remoteVersion)) {
                // If the specific version requested by server is unsafe, we abort.
                // If we are just scanning the list, we skip this invalid entry.
                if (gettingServerVersion) return false;
                continue;
            }

            // Install
            LOGGER.info("Update found! Updating from {} to {}", AM_VERSION, rawRemoteVersion);
            installModVersion(automodpack);
            return true;
        }

        if (!gettingServerVersion) LOGGER.info("No suitable updates found.");
        return false;
    }

    /**
     * Checks if the target update is safe.
     * Prevents downgrading below 4.0.0 Stable.
     * * Logic:
     * 4.0.0 (Stable) is SAFE.
     * 4.1.0 (Stable) is SAFE.
     * 4.0.0-betaX is UNSAFE (because it is < 4.0.0 Stable).
     */
    public static boolean validUpdate(SemanticVersion remoteVersion) {
        if (remoteVersion.compareTo(MINIMUM_SAFE_VERSION) < 0) {
            LOGGER.error("Downgrading AutoModpack to version {} is strongly discouraged/disabled due to security concerns (Target is older than 4.0.0 Stable).", remoteVersion);
            return false;
        }
        return true;
    }

    public static void installModVersion(ModrinthAPI automodpack) {
        Path automodpackUpdateJar = automodpackDir.resolve(automodpack.fileName());
        Path newAutomodpackJar;

        try {
            DownloadManager downloadManager = new DownloadManager();
            new ScreenManager().download(downloadManager, "AutoModpack " + automodpack.fileVersion());

            downloadManager.download(
                    automodpackUpdateJar,
                    automodpack.SHA1Hash(),
                    List.of(automodpack.downloadUrl()),
                    automodpack.fileSize(),
                    () -> LOGGER.info("Downloaded update for AutoModpack."),
                    () -> LOGGER.error("Failed to download update for AutoModpack.")
            );

            downloadManager.joinAll();
            downloadManager.cancelAllAndShutdown();

            addOverridesToJar(automodpackUpdateJar);

            newAutomodpackJar = THIS_MOD_JAR.getParent().resolve(automodpackUpdateJar.getFileName());

            var updateType = UpdateType.AUTOMODPACK;
            var relauncher = new ReLauncher(updateType);

            Runnable callback = () -> {
                SmartFileUtils.executeOrder66(THIS_MOD_JAR);
                LOGGER.info("Successfully updated AutoModpack! Restarting...");
            };

            SmartFileUtils.copyFile(automodpackUpdateJar, newAutomodpackJar);
            SmartFileUtils.executeOrder66(automodpackUpdateJar); // Delete temp file

            relauncher.restart(true, callback);
        } catch (Exception e) {
            LOGGER.error("Failed to update! " + e);
        }
    }

    public static Optional<InputStream> getJarEntryInputStream(Path jarFilePath, String entryName) throws IOException {
        try (InputStream fileStream = new LockFreeInputStream(jarFilePath);
            ZipInputStream zipStream = new ZipInputStream(fileStream)) {
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return Optional.of(zipStream);
                }
            }
        }

        return Optional.empty();
    }

    public static void addOverridesToJar(Path jarFilePath) throws IOException {
        if (clientConfigOverride == null || clientConfigOverride.isBlank()) {
            return;
        }

        if (!Files.isRegularFile(jarFilePath) || !Files.isRegularFile(THIS_MOD_JAR)) {
            LOGGER.error("Jar file of updated AutoModpack not found!");
            return;
        }

        Path tempJarPath = Files.createTempFile("tempAutoModpackJar", ".jar");

        try (JarFile jarFile = new JarFile(jarFilePath.toFile());
             JarOutputStream tempJarOutputStream = new JarOutputStream(Files.newOutputStream(tempJarPath))) {

            jarFile.stream().forEach(entry -> {
                try {
                    JarEntry newEntry = new JarEntry(entry.getName());
                    tempJarOutputStream.putNextEntry(newEntry);
                    try (InputStream entryInputStream = jarFile.getInputStream(entry)) {
                        entryInputStream.transferTo(tempJarOutputStream);
                    }
                    tempJarOutputStream.closeEntry();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            Optional<InputStream> txtInputStreamOpt = getJarEntryInputStream(THIS_MOD_JAR, clientConfigFileOverrideResource);
            if (txtInputStreamOpt.isPresent()) {
                JarEntry newTxtEntry = new JarEntry(clientConfigFileOverrideResource);
                tempJarOutputStream.putNextEntry(newTxtEntry);
                txtInputStreamOpt.get().transferTo(tempJarOutputStream);
                tempJarOutputStream.closeEntry();
            }
        }

        Files.move(tempJarPath, jarFilePath, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("Added config overrides to the updated AutoModpack JAR");
    }
}