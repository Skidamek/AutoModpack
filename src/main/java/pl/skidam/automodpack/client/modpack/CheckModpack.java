package pl.skidam.automodpack.client.modpack;

import pl.skidam.automodpack.utils.ToastExecutor;
import pl.skidam.automodpack.utils.WebFileSize;

import java.io.*;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class CheckModpack {

    public CheckModpack() {

        // if latest modpack is not same as current modpack download new mods.
        // Check how big the Modpack file is
        if (link == null || link.equals("null")) {

            LOGGER.info("No modpack link found!");
            // TODO
            ModpackUpdated = "false";
            return;
        }

        LOGGER.info("Checking if modpack is up-to-date...");

        File Modpack = new File("./AutoModpack/modpack.zip");
        long currentSize = Modpack.length();
        long latestSize = Long.parseLong(WebFileSize.webfileSize(link));

        if (latestSize == 0) {
            ModpackUpdated = "false";
            return;
        }

        if (!Modpack.exists() || currentSize != latestSize) {
            LOGGER.info("Downloading modpack!");
            new ToastExecutor(1);
            new DownloadModpack();
            return;
        }

        LOGGER.info("Didn't found any updates for modpack!");
        new ToastExecutor(3);
        new UnZip(out, "false");
    }
}
