package pl.skidam.automodpack;

import pl.skidam.automodpack.client.ScreenTools;
import pl.skidam.automodpack.client.ui.AutoModpackToast;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.ModrinthAPI;

import java.io.IOException;

import static pl.skidam.automodpack.StaticVariables.*;

public class SelfUpdater {
    public SelfUpdater() {

        if (Platform.isDevelopmentEnvironment()) {
            return;
        }

        if (Platform.getEnvironmentType().equals("SERVER")) {
            if (!serverConfig.selfUpdater) return;
        }

        if (Platform.getEnvironmentType().equals("CLIENT")) {
            if (!clientConfig.selfUpdater) return;
        }

        // TODO remove this when forge version will be published, if at all...
        if (Platform.Forge) {
            LOGGER.warn("Self updater is disabled for Forge version at the moment :/");
            return;
        }

        LOGGER.info("Checking if AutoModpack is up-to-date...");


        ModrinthAPI automodpack = new ModrinthAPI("k68glP2e");

        if (automodpack == null || automodpack.modrinthAPIversion == null) {
            LOGGER.error("Couldn't get latest version of AutoModpack from Modrinth API (request url: {})", automodpack.modrinthAPIrequestUrl);
            return;
        }

        // If latest mod is not same as current mod download new mod.
        // Check how big the mod file is
        if (automodpack.modrinthAPIversion.contains("-")) {
            automodpack.modrinthAPIversion = automodpack.modrinthAPIversion.split("-")[0];
        }

        String LATEST_VERSION = automodpack.modrinthAPIversion.replace(".", "");
        String OUR_VERSION = VERSION.replace(".", "");

        if (LATEST_VERSION == null || OUR_VERSION == null) {
            LOGGER.error("Latest version or current version is null. Likely automodpack isn't updated to your version of minecraft yet");
            AutoModpackToast.add(5);
            return;
        }

        try {
            if (Integer.parseInt(OUR_VERSION) > Integer.parseInt(LATEST_VERSION)) {
                LOGGER.info("You are using pre-released or beta version of AutoModpack: " + VERSION + " latest stable version is: " + automodpack.modrinthAPIversion);
                AutoModpackToast.add(4);
                return;
            }
        } catch (NumberFormatException e) {
            // ignore

            if (OUR_VERSION.contains("beta") && !LATEST_VERSION.contains("beta")) {

                OUR_VERSION = OUR_VERSION.replaceAll("[^0-9]", "");
                LATEST_VERSION = LATEST_VERSION.replaceAll("[^0-9]", "");

                if (Integer.parseInt(OUR_VERSION) >= Integer.parseInt(LATEST_VERSION)) {
                    LOGGER.info("You are using pre-released or beta version of AutoModpack: " + VERSION + " latest stable version is: " + automodpack.modrinthAPIversion);
                    AutoModpackToast.add(4);
                    return;
                }
            } // we don't want to auto update to beta version, but from beta to newer release, yes.
        }


        if (OUR_VERSION.equals(LATEST_VERSION) || !automodpack.modrinthAPIversionType.equals("release")) {
            LOGGER.info("Didn't find any updates for AutoModpack! You are on the latest version: " + VERSION);
            AutoModpackToast.add(4);
            return;
        }

        LOGGER.info("Update found! Updating to new version: " + automodpack.modrinthAPIversion);
        AutoModpackToast.add(2);
        ScreenTools.setTo.download();

        try {
            Download downloadInstance = new Download();

            downloadInstance.download(automodpack.modrinthAPIdownloadUrl, automodpackUpdateJar); // Download it

            String localChecksum = CustomFileUtils.getHashWithRetry(automodpackUpdateJar, "SHA-512");

            if (!localChecksum.equals(automodpack.modrinthAPISHA512Hash)) {
                LOGGER.error("Checksums are not the same! Downloaded file is corrupted!");
                AutoModpackToast.add(5);
                return;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to update myself!");
            AutoModpackToast.add(5);
            return;
        }

        // Shutdown hook to make it the most reliable way to update the mod
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Running Shutdown Hook -- AutoModpack selfupdater");

            try {
                CustomFileUtils.copyFile(automodpackUpdateJar, automodpackJar);
            } catch (IOException e) {
                System.out.println("Error while copying file!");
                e.printStackTrace();
            }

            CustomFileUtils.forceDelete(automodpackUpdateJar, true);
            System.out.println("Finished Shutdown Hook -- AutoModpack selfupdater!");
        }));

        LOGGER.info("Successfully downloaded update, waiting for shutdown");

        new ReLauncher.Restart(null, "Successfully updated AutoModpack - " + automodpack.modrinthAPIversion);
    }
}