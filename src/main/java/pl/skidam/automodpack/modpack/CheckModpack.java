package pl.skidam.automodpack.modpack;

import pl.skidam.automodpack.AutoModpackClient;
import pl.skidam.automodpack.utils.Error;
import pl.skidam.automodpack.utils.ToastExecutor;
import pl.skidam.automodpack.utils.WebFileSize;

import java.io.*;

import static pl.skidam.automodpack.AutoModpackClient.*;

public class CheckModpack {

    public CheckModpack() {

        Thread.currentThread().setPriority(10);

        // if latest modpack is not same as current modpack download new mods.
        // Check how big the Modpack file is
        File Modpack = new File("./AutoModpack/modpack.zip");

        if (!Modpack.exists()) {
            AutoModpackClient.LOGGER.info("Didn't found modpack, downloading modpack!");
            new ToastExecutor(1);
            new DownloadModpack();
            return;
        }

        AutoModpackClient.LOGGER.info("Checking if modpack is up-to-date...");

        long currentSize = Modpack.length();
        long latestSize;
        try {
            latestSize = Long.parseLong(WebFileSize.webfileSize(link));
        } catch (Exception e) {
            AutoModpackClient.LOGGER.error("Make sure that you have an internet connection!");
            new Error();
            new UnZip(out, false, "false");
            return;
        }

        if (currentSize != latestSize) {
            AutoModpackClient.LOGGER.info("Update found! Downloading new mods!");
            new ToastExecutor(1);
            new DownloadModpack();
            return;
        }

        if (latestSize == 0) {
            new Error();
            return;
        }

        AutoModpackClient.LOGGER.info("Didn't found any updates for modpack!");
        new ToastExecutor(3);
        new UnZip(out, false, "false");
    }
}
