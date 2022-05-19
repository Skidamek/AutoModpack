package pl.skidam.automodpack;

import pl.skidam.automodpack.utils.Error;
import pl.skidam.automodpack.utils.ToastExecutor;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackClient.*;

public class SelfUpdater {

    File selfBackup = new File("./AutoModpack/AutoModpack.jar");
    File oldAutoModpack = new File("./AutoModpack/OldAutoModpack/AutoModpack.jar");
    boolean LatestVersion = false;

    public SelfUpdater() {

        // if latest mod is not same as current mod download new mod.
        // Check how big the mod file is
        if (!selfBackup.exists()) { new ToastExecutor(2); }
        if (selfBackup.exists()) {
            AutoModpackClient.LOGGER.info("Checking if AutoModpack is up-to-date...");

            long currentSize = selfBackup.length();
            long latestSize = 0;
            try {
                latestSize = Long.parseLong(webfileSize());
            } catch (Exception e) {
                AutoModpackClient.AutoModpackUpdated = "false";
                AutoModpackClient.LOGGER.error("Make sure that you have an internet connection!");
                new Error();
                return;
            }

            if (currentSize != latestSize) {
                AutoModpackClient.LOGGER.info("Update found! Updating!");
                new ToastExecutor(2);

            } else {
                AutoModpackClient.LOGGER.info("Didn't found any updates for AutoModpack!");
                new ToastExecutor(4);
                LatestVersion = true;
                AutoModpackClient.AutoModpackUpdated = "false";
            }
        }


        if (!LatestVersion || !selfBackup.exists()) {

            File oldAM = new File("./AutoModpack/OldAutoModpack/");
            if (!oldAM.exists()) {
                oldAM.mkdir();
            }
            File selfOutFile = new File("./mods/AutoModpack.jar");
            if (selfOutFile.exists()) {
                try {
                    Files.copy(selfOut.toPath(), oldAutoModpack.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                AutoModpackClient.LOGGER.error("LoL how did you get here? You should have the AutoModpack.jar in your mods folder.");
            }

//            new Download(selfLink, selfOut);

            try {
                URL url = new URL(selfLink);
                HttpURLConnection http = (HttpURLConnection) url.openConnection();
                double fileSize = (double) http.getContentLengthLong();
                BufferedInputStream in = new BufferedInputStream(http.getInputStream());
                FileOutputStream fos = new FileOutputStream(selfOut);
                BufferedOutputStream bout = new BufferedOutputStream(fos, 1024);
                byte[] buffer = new byte[1024];
                double downloaded = 0.00;
                int read;
                double percentDownloaded;
                String lastPercent = null;
                String percent = null;

                while ((read = in.read(buffer, 0, 1024)) >= 0) {
                    bout.write(buffer, 0, read);
                    downloaded += read;
                    percentDownloaded = (downloaded * 100) / fileSize;

                    // if lastPercent != percent
                    if (!Objects.equals(lastPercent, percent)) {
                        percent = (String.format("%.0f", percentDownloaded));
                        AutoModpackClient.LOGGER.info(percent + "%");
                        lastPercent = percent;

                        // if lastPercent == percent
                    } else {
                        percent = (String.format("%.0f", percentDownloaded));
                    }
                }
                bout.close();
                in.close();

                Files.copy(selfOut.toPath(), selfBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);

                AutoModpackClient.LOGGER.info("Successfully self updated!");
//                new ToastExecutor(6);
                AutoModpackClient.AutoModpackUpdated = "true";

            } catch (IOException ex) {
                AutoModpackClient.LOGGER.error("Failed to update myself!");
                new ToastExecutor(5);
                AutoModpackClient.AutoModpackUpdated = "false";
                ex.printStackTrace();
            }
        } else {
            AutoModpackClient.AutoModpackUpdated = "false";
        }
    }

    private String webfileSize() {
        String webfileSize = "";
        try {
            URL url = new URL(selfLink);
            URLConnection conn = url.openConnection();
            webfileSize = conn.getHeaderField("Content-Length");
        } catch (IOException e) {
            e.printStackTrace();
        }

        return webfileSize;
    }

    private static void wait(int ms) {
        try
        {
            Thread.sleep(ms);
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }
}
