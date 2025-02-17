package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
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

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class SelfUpdater {

    public static final String AUTOMODPACK_ID = "k68glP2e"; // AutoModpack modrinth id

    public static boolean update () {
        return update(null);
    }

    public static boolean update(Jsons.ModpackContentFields serverModpackContent) {

        if (LOADER_MANAGER.isDevelopmentEnvironment()) return false;
        if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER && !serverConfig.selfUpdater) {
            LOGGER.info("AutoModpack self-updater is disabled in server config.");
            return false;
        }

        List<ModrinthAPI> modrinthAPIList = new ArrayList<>();
        boolean gettingServerVersion = false;

        // Check if server version is available
        if (serverModpackContent != null && serverModpackContent.automodpackVersion != null) {
            modrinthAPIList.add(ModrinthAPI.getModSpecificVersion(AUTOMODPACK_ID, serverModpackContent.automodpackVersion, serverModpackContent.mcVersion));
            gettingServerVersion = true;
        } else {
            modrinthAPIList = ModrinthAPI.getModInfosFromID(AUTOMODPACK_ID);
        }

        if (gettingServerVersion) {
            LOGGER.info("Syncing AutoModpack to server version: {}", serverModpackContent.automodpackVersion);
        } else if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT && !clientConfig.selfUpdater) {
            LOGGER.info("AutoModpack self-updater is disabled in client config.");
            return false;
        } else {
            LOGGER.info("Checking if AutoModpack is up-to-date...");
        }

        String message = "Couldn't get latest version of AutoModpack from Modrinth API. Likely automodpack isn't updated to your version of minecraft yet...";

        if (modrinthAPIList == null) {
            LOGGER.warn(message);
            return false;
        }

        // Modrinth APIs are sorted from latest to oldest
        for (ModrinthAPI automodpack : modrinthAPIList) {

            if (automodpack == null || automodpack.fileVersion() == null) {
                message = "Couldn't get latest version of AutoModpack from Modrinth API. Likely automodpack isn't updated to your version of minecraft yet...";
                continue;
            }

            // Version scheme: major.minor.patch[-betaX]
            String fileVersion = automodpack.fileVersion();

            // Compare versions as strings
            if (fileVersion.equals(AM_VERSION)) {
                message = "Didn't find any updates for AutoModpack!";
                continue;
            }

            boolean currentBeta = AM_VERSION.contains("-beta");
            boolean remoteBeta = fileVersion.contains("-beta");

            String[] currentVersionSplit = AM_VERSION.split("-beta");
            String[] remoteVersionSplit = fileVersion.split("-beta");

            // Removes '-betaX' if exists
            // Removes '.' - dots - to then parse it as number
            String OUR_VERSION = currentVersionSplit[0].replace(".", "");
            String LATEST_VERSION = remoteVersionSplit[0].replace(".", "");

            // Compare versions as numbers
            if (!gettingServerVersion) {
                try {
                    if (Integer.parseInt(OUR_VERSION) > Integer.parseInt(LATEST_VERSION)) {
                        message = "You are using pre-released or beta version of AutoModpack: " + AM_VERSION + " latest stable version is: " + automodpack.fileVersion();
                        break; // Break, checked version is lower than installed, meaning that higher version doesn't exist.
                    }
                } catch (NumberFormatException e) {
                    LOGGER.error("Failed to parse version numbers: " + e);
                }

                if (currentBeta && remoteBeta) {
                    if (Integer.parseInt(currentVersionSplit[1]) > Integer.parseInt(remoteVersionSplit[1])) {
                        message = "You are using pre-released or beta version of AutoModpack: " + AM_VERSION + " latest stable version is: " + automodpack.fileVersion();
                        break; // Break, checked version is lower than installed, meaning that higher version doesn't exist.
                    }
                }

                if (!currentBeta && remoteBeta) {
                    message = "You are using stable version of AutoModpack: " + AM_VERSION + " latest pre-released or beta version is: " + automodpack.fileVersion();
                    continue;
                }

                if (currentBeta && !remoteBeta) {
                    message = "You are using pre-released or beta version of AutoModpack: " + AM_VERSION + " latest stable version is: " + automodpack.fileVersion();
                    continue;
                }
            }

            // Compare sha1 hash
            if (automodpack.SHA1Hash().equals(CustomFileUtils.getHash(AUTOMODPACK_JAR))) {
                message = "Didn't find any updates for AutoModpack! You are on the latest version: " + AM_VERSION;
                break; // Break, we are using the latest version, all previous if's get us to this point meaning otherwise we would update to this version, but we are already using it.
            }

            LOGGER.info("Update found! Updating to the {} version: {}", gettingServerVersion ? "server" : "latest", automodpack.fileVersion());

            // We got correct version
            // We are currently using outdated snapshot or outdated release version
            // If latest is release, always update
            // If latest is beta/alpha (snapshot), update only if we are using beta/alpha (snapshot)
            installModVersion(automodpack);
            return true;
        }

        LOGGER.info(message);
        return false;
    }

    public static void installModVersion(ModrinthAPI automodpack) {
        Path automodpackUpdateJar = automodpackDir.resolve(automodpack.fileName());
        Path newAutomodpackJar;

        try {
            DownloadManager downloadManager = new DownloadManager();

            new ScreenManager().download(downloadManager, "AutoModapck " + automodpack.fileVersion());

            // Download it
            downloadManager.download(
                    automodpackUpdateJar,
                    automodpack.SHA1Hash(),
                    new DownloadManager.Urls().addUrl(new DownloadManager.Url().getUrl(automodpack.downloadUrl())),
                    () -> LOGGER.info("Downloaded update for AutoModpack."),
                    () -> LOGGER.error("Failed to download update for AutoModpack.")
            );

            downloadManager.joinAll();
            downloadManager.cancelAllAndShutdown();

            addOverridesToJar(automodpackUpdateJar);

            newAutomodpackJar = AUTOMODPACK_JAR.getParent().resolve(automodpackUpdateJar.getFileName());

            // preload classes
            new ReLauncher();
            var updateType = UpdateType.AUTOMODPACK;

            CustomFileUtils.copyFile(automodpackUpdateJar, newAutomodpackJar);
            CustomFileUtils.forceDelete(automodpackUpdateJar);
            new ReLauncher(updateType).restart(true, () -> {
                CustomFileUtils.forceDelete(AUTOMODPACK_JAR);
                LOGGER.info("Successfully updated AutoModpack!");
            });
        } catch (Exception e) {
            LOGGER.error("Failed to update! " + e);
        }

        // As far as i know there isn't really a way to force jvm to unload our old jar, so we need to restart...
        // This doesn't seem to work as expected
        // new SetupMods().removeMod(AUTOMODPACK_JAR);
        // System.gc();
        // new SetupMods().addMod(newAutomodpackJar);
        // System.gc();
        // CustomFileUtils.forceDelete(AUTOMODPACK_JAR);
        // CustomFileUtils.forceDelete(automodpackUpdateJar);
        //
        // java.io.UncheckedIOException: java.nio.file.NoSuchFileException: ~/.minecraft/mods/automodpack-fabric-4.0.0-beta1-1.20.1.jar
        //	at net.fabricmc.loader.impl.util.LoaderUtil.normalizeExistingPath(LoaderUtil.java:46)
        //	at net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.getCodeSource(KnotClassDelegate.java:515)
        //	at net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.getMetadata(KnotClassDelegate.java:363)
        //	at net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.tryLoadClass(KnotClassDelegate.java:338)
        //	at net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.loadClass(KnotClassDelegate.java:218)
        //	at net.fabricmc.loader.impl.launch.knot.KnotClassLoader.loadClass(KnotClassLoader.java:119)
        //	at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:525)
        //  at pl.skidam.automodpack_loader_core.Preload     // <--- This is where new updated jar starts to do it's stuff
        //
        // That happens when updating from `automodpack-fabric-4.0.0-beta1-1.20.1.jar` to `automodpack-fabric-4.0.0-beta2-1.20.1.jar`
        // It still tries to load classes from the old jar
        //
        // The only solutions i see to fix this are:
        //   1. Restart the game...
        //   2. Rearrange our mod structure to have a separate jar only for updating the AutoModpack and then conditionally load automodpack mod jar, but with sacrificing the ability to update the 'this' updating code...
        //   3. Remove the self-updating feature and let the user update the mod manually - however this would entirely break the purpose of this mod since when server would update to new version client might stop being compatible...
        //
        // For now let's just restart the game since it's simpler
        // TODO - implement a way to conditionally load automodpack mod jar without breaking the jvm and hope that self-updating logic won't need to be updated anymore
    }

    // Helper method to get an InputStream for a JAR entry (if present)
    public static Optional<InputStream> getJarEntryInputStream(Path jarFilePath, String entryName) throws IOException {
        try (JarFile jarFile = new JarFile(jarFilePath.toFile())) {
            JarEntry jarEntry = jarFile.getJarEntry(entryName);
            if (jarEntry != null) {
                return Optional.of(jarFile.getInputStream(jarEntry));
            }
        }
        return Optional.empty();
    }

    public static void addOverridesToJar(Path jarFilePath) throws IOException {
        if (clientConfigOverride == null || clientConfigOverride.isBlank()) {
            return;
        }

        if (!Files.isRegularFile(jarFilePath) || !Files.isRegularFile(AUTOMODPACK_JAR)) {
            LOGGER.error("Jar file of updated AutoModpack not found!");
            return;
        }

        // Create a temp JAR file to write the updated contents
        Path tempJarPath = Files.createTempFile("tempAutoModpackJar", ".jar");

        try (JarFile jarFile = new JarFile(jarFilePath.toFile());
             JarOutputStream tempJarOutputStream = new JarOutputStream(Files.newOutputStream(tempJarPath))) {

            // Copy original JAR entries to the temp JAR
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

            // Add the new file as a new entry in the JAR root
            Optional<InputStream> txtInputStreamOpt = getJarEntryInputStream(AUTOMODPACK_JAR, clientConfigFileOverrideResource);
            if (txtInputStreamOpt.isPresent()) {
                JarEntry newTxtEntry = new JarEntry(clientConfigFileOverrideResource);
                tempJarOutputStream.putNextEntry(newTxtEntry);
                txtInputStreamOpt.get().transferTo(tempJarOutputStream);
                tempJarOutputStream.closeEntry();
            }
        }

        // Replace the old JAR file with the new one (rename temp JAR)
        Files.move(tempJarPath, jarFilePath, StandardCopyOption.REPLACE_EXISTING);

        LOGGER.info("Added config overrides to the updated AutoModpack JAR");
    }
}