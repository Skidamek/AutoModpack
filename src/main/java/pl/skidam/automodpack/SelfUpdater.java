package pl.skidam.automodpack;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.modpack.Error;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public class SelfUpdater implements Runnable {

    String selfLink = "https://github.com/Skidamek/AutoModpack/releases/download/pipel/AutoModpack.jar";
    File selfOut = new File( "./mods/AutoModpack.jar");
    int delay;

    public SelfUpdater(int delay) {
        this.delay = delay;
    }

    File selfBackup = new File("./AutoModpack/AutoModpack.jar");
    File oldAutoModpack = new File("./AutoModpack/OldAutoModpack/AutoModpack.jar");

    boolean LatestVersion = false;

    @Override
    public void run() {

        while (MinecraftClient.getInstance().currentScreen == null) {
            wait(100);
        }

        wait(delay);

        Thread.currentThread().setPriority(10);

        // if latest mod is not same as current mod download new mod.
        // Check how big the mod file is
        if (selfBackup.exists()) {
            AutoModpack.LOGGER.info("Checking if AutoModpack is up-to-date...");

            long currentSize = selfBackup.length();
            long latestSize = 0;
            try {
                latestSize = Long.parseLong(webfileSize());
            } catch (Exception e) {
                AutoModpack.LOGGER.error("Make sure that you have an internet connection!");
                new Error();
                return;
            }

            if (currentSize != latestSize) {
                AutoModpack.LOGGER.info("Update found! Updating!");
                new ToastExecutor(2);

            } else {
                AutoModpack.LOGGER.info("Didn't found any updates for AutoModpack!");
                new ToastExecutor(4);
                LatestVersion = true;
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
                AutoModpack.LOGGER.error("LoL how did you get here? You should have the AutoModpack.jar in your mods folder.");
            }

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
                        AutoModpack.LOGGER.info(percent + "%");
                        lastPercent = percent;

                        // if lastPercent == percent
                    } else {
                        percent = (String.format("%.0f", percentDownloaded));
                    }
                }
                bout.close();
                in.close();

                Files.copy(selfOut.toPath(), selfBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);

                AutoModpack.LOGGER.info("Successfully self updated!");
                new ToastExecutor(6);
                new Finished(false, true, false);

            } catch (IOException ex) {
                AutoModpack.LOGGER.error("Failed to update myself!");
                new ToastExecutor(8);
                ex.printStackTrace();
            }
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
