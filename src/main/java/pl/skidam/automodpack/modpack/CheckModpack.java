package pl.skidam.automodpack.modpack;

import pl.skidam.automodpack.AutoModpackClient;
import pl.skidam.automodpack.utils.Error;
import pl.skidam.automodpack.utils.ToastExecutor;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;

import static pl.skidam.automodpack.AutoModpackClient.*;

public class CheckModpack {

    public CheckModpack() {

        boolean Error = false;
        boolean LatestVersion = false;

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
        long latestSize = 0;
        try {
            latestSize = Long.parseLong(webfileSize());
        } catch (Exception e) {
            AutoModpackClient.LOGGER.error("Make sure that you have an internet connection!");
            new Error();
            new UnZip(out, Error, "false");
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
            Error = true;
            return;
        }

        AutoModpackClient.LOGGER.info("Didn't found any updates for modpack!");
        new ToastExecutor(3);
        LatestVersion = true;
        new UnZip(out, Error, "false");
    }


    // GITHUB COPILOT, I LOVE YOU!!!
    public String webfileSize() {
        String size = "";
        try {
            URL url = new URL(link);
            URLConnection conn = url.openConnection();
            size = conn.getHeaderField("Content-Length");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return size;  // returns the size of the file in bytes
    }
}
