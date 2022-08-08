package pl.skidam.automodpack.client.modpack;

import pl.skidam.automodpack.client.AutoModpackToast;
import pl.skidam.automodpack.utils.InternetConnectionCheck;
import pl.skidam.automodpack.utils.WebFileSize;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class CheckModpack {

    public static boolean isCheckUpdatesButtonClicked;

    public CheckModpack(boolean preload) {

        // If the latest modpack is not same as current modpack download new mods.
        // Check how big the Modpack file is
        if (link == null || link.equals("null")) {
            LOGGER.info("No modpack link found!");
            ModpackUpdated = "false";
            return;
        }

        LOGGER.info("Checking if modpack is up-to-date...");

        if (!InternetConnectionCheck.InternetConnectionCheck(link)) return;

        long currentSize = out.length();
        LOGGER.info("Current modpack size: " + currentSize);

        if (currentSize == 0) {
            LOGGER.info("Downloading modpack!");
            if (!preload) {
                AutoModpackToast.add(1);
            }
            new DownloadModpack.prepare(preload);
            return;
        }


        long latestSize = WebFileSize.webfileSize(link);
        LOGGER.info("Latest modpack size: " + latestSize);

        if (latestSize == 0) {
            ModpackUpdated = "false";
            return;
        }

        if (!out.exists() || currentSize != latestSize) {
            LOGGER.info("Downloading modpack!");
            if (!preload) {
                AutoModpackToast.add(1);
            }
            new DownloadModpack.prepare(preload);
            return;
        }

        LOGGER.info("Didn't find any updates for modpack!");
        if (!preload) {
            AutoModpackToast.add(3);
        }
        if (isCheckUpdatesButtonClicked) {
            isCheckUpdatesButtonClicked = false;
            new UnZip(out, "false");
        } else {
            ModpackUpdated = "false";
        }
    }

}
