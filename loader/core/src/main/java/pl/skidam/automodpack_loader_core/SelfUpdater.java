package pl.skidam.automodpack_loader_core;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.loader.LoaderService;
import pl.skidam.automodpack_loader_core.mods.SetupMods;
import pl.skidam.automodpack_loader_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class SelfUpdater {

    public static final String automodpackID = "k68glP2e"; // AutoModpack modrinth id
    public final static Path automodpackJar;

    static {
        try {
            URI uri = SelfUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            automodpackJar = Paths.get(uri).toAbsolutePath().normalize();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static void update () {
        update(null);
    }

    public static void update(Jsons.ModpackContentFields serverModpackContent) {

        if (new LoaderManager().isDevelopmentEnvironment()) {
            return;
        }

        if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.SERVER) {
            if (!serverConfig.selfUpdater) return;
        }

        if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.CLIENT) {
            if (!clientConfig.selfUpdater) return;
        }

        LOGGER.info("Checking if AutoModpack is up-to-date...");

        List<ModrinthAPI> modrinthAPIList = new ArrayList<>();
        boolean gettingServerVersion;

        // Check if server version is available
        if (serverModpackContent != null && serverModpackContent.automodpackVersion != null) {
            modrinthAPIList.add(ModrinthAPI.getModSpecificVersion(automodpackID, serverModpackContent.automodpackVersion, serverModpackContent.mcVersion));
            gettingServerVersion = true;
        } else {
            modrinthAPIList = ModrinthAPI.getModInfosFromID(automodpackID);
            gettingServerVersion = false;
        }

        String message = "Couldn't get latest version of AutoModpack from Modrinth API. Likely automodpack isn't updated to your version of minecraft yet...";

        if (modrinthAPIList == null) {
            LOGGER.warn(message);
            return;
        }

        // Modrinth APIs are sorted from latest to oldest
        for (ModrinthAPI automodpack : modrinthAPIList) {

            if (automodpack == null || automodpack.fileVersion() == null) {
                message = "Couldn't get latest version of AutoModpack from Modrinth API. Likely automodpack isn't updated to your version of minecraft yet...";
                continue;
            }

            String fileVersion = automodpack.fileVersion();

            if (automodpack.fileVersion().contains("-")) {
                fileVersion = automodpack.fileVersion().split("-")[0];
            }

            String LATEST_VERSION = fileVersion.replace(".", "");
            String OUR_VERSION = AM_VERSION.replace(".", "");
            boolean remoteSnapshot = false;

            if (!gettingServerVersion) {
                try {
                    if (Integer.parseInt(OUR_VERSION) > Integer.parseInt(LATEST_VERSION)) {
                        message = "You are using pre-released or beta version of AutoModpack: " + AM_VERSION + " latest stable version is: " + automodpack.fileVersion();
                        break; // Break, checked version is lower than installed, meaning that higher version doesn't exist.
                    }
                } catch (NumberFormatException e) {
                    // ignore

                    // Check if version has any other characters than numbers and if latest version is only numbers
                    if (OUR_VERSION.chars().anyMatch(ch -> !Character.isDigit(ch))) {

                        remoteSnapshot = true;

                        OUR_VERSION = OUR_VERSION.replaceAll("[^0-9]", "");
                        LATEST_VERSION = LATEST_VERSION.replaceAll("[^0-9]", "");

                        if (Integer.parseInt(OUR_VERSION) > Integer.parseInt(LATEST_VERSION)) {
                            message = "You are using pre-released or beta version of AutoModpack: " + AM_VERSION + " latest stable version is: " + automodpack.fileVersion();
                            break; // Break, checked version is lower than installed, meaning that higher version doesn't exist.
                        }
                    }
                }
            }

            if (!remoteSnapshot) {
                // We always want to update to latest release version unless server is already using snapshot version
                if (gettingServerVersion && OUR_VERSION.equals(LATEST_VERSION)) {
                    message = "Didn't find any updates for AutoModpack! You are on the server version: " + AM_VERSION;
                    break; // Break, server can provide only one version.
                }

                if (!gettingServerVersion && (OUR_VERSION.equals(LATEST_VERSION) || !"release".equals(automodpack.releaseType()))) {
                    message = "Didn't find any updates for AutoModpack! You are on the latest version: " + AM_VERSION;
                    continue;
                }
            }

            // We got correct version
            // We are currently using outdated snapshot or outdated release version
            // If latest is release, always update
            // If latest is beta/alpha (snapshot), update only if we are using beta/alpha (snapshot)
            if (!gettingServerVersion) {
                LOGGER.info("Update found! Updating to latest version: " + automodpack.fileVersion());
            } else {
                LOGGER.info("Update found! Updating to the server version: " + automodpack.fileVersion());
            }

            installModVersion(automodpack);
            return;
        }

        LOGGER.info(message);
    }

    public static void installModVersion(ModrinthAPI automodpack) {
        Path automodpackUpdateJar = Paths.get(automodpackDir + File.separator + automodpack.fileName());
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

            // We assume that update jar has always different name than current jar
            newAutomodpackJar = Path.of(automodpackJar.getParent() + File.separator + automodpackUpdateJar.getFileName());
            CustomFileUtils.copyFile(automodpackUpdateJar, newAutomodpackJar);
            CustomFileUtils.forceDelete(automodpackUpdateJar);
        } catch (Exception e) {
            LOGGER.error("Failed to update! " + e);
            return;
        }

        if (preload) {
            new SetupMods().removeMod(automodpackJar);
            new SetupMods().removeMod("automodpack");
            new SetupMods().addMod(newAutomodpackJar);
            LOGGER.info("Successfully downloaded and installed update!");
        }

        CustomFileUtils.forceDelete(automodpackJar);

        if (!preload) {
            LOGGER.info("Successfully downloaded update, waiting for shutdown");
            new ReLauncher.Restart("Successfully updated AutoModpack - " + automodpack.fileVersion(), UpdateType.AUTOMODPACK);
        }
    }
}