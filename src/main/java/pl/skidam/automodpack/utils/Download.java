package pl.skidam.automodpack.utils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class Download {

    public static float progress;
    public static String internetConnectionSpeed;
    private static long startTime;

    public static boolean Download(String link, File output) {
        try {
            URL url = new URL(link);

            // TODO get minecraft username
//            URLConnection conn = url.openConnection();
//
//
//            conn.setRequestProperty("X-Minecraft-Username", HERE);
//            conn.connect();
//            conn.getInputStream().close();

            progress = 0;
            internetConnectionSpeed = "";
            CompletableFuture.runAsync(() -> {
                startTime = System.currentTimeMillis();
                new Wait(2000);
                while (progress > 0 && progress < 100) {
                    startTime = startTime + 1000;
                    new Wait(2000);
                }
            });

            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            http.setRequestMethod("GET");
            http.setConnectTimeout(30000); // 30 seconds
            int responseCode = http.getResponseCode();
            if (responseCode == 200) {
                double fileSize = (double) http.getContentLengthLong();
                BufferedInputStream in = new BufferedInputStream(http.getInputStream());
                FileOutputStream fos = new FileOutputStream(output);
                BufferedOutputStream bout = new BufferedOutputStream(fos, 1024);
                byte[] buffer = new byte[1024];
                double downloaded = 0.00;
                int read;
                double percentDownloaded;
                String lastPercent = null;
                String percent = "0";

                while ((read = in.read(buffer, 0, 1024)) >= 0) {
                    bout.write(buffer, 0, read);
                    downloaded += read;
                    percentDownloaded = (downloaded * 100) / fileSize;

                    long endTime = System.currentTimeMillis();
                    double rate = (((downloaded / 1024) / ((endTime - startTime) / 1000.0)) * 8);
                    rate = Math.round( rate * 100.0 ) / 100.0;
                    if (rate > 1000) {
                        internetConnectionSpeed = String.format("%.1f", (rate / 1024)).concat(" Mb/s");
                    } else {
                        internetConnectionSpeed = String.format("%.1f", rate).concat(" Kb/s");
                    }

                    // if lastPercent != percent
                    if (!Objects.equals(lastPercent, percent)) {
                        percent = (String.format("%.1f", percentDownloaded));
                        progress = Float.parseFloat(percent);
                        if (percent.contains("0.0") && !percent.equals("0.0")) {
                            LOGGER.info("Downloaded " + percent.split("\\.")[0] + "%" + " with internet connection speed of " + internetConnectionSpeed);
                        }
                        lastPercent = percent;

                        // if lastPercent == percent
                    } else {
                        percent = (String.format("%.1f", percentDownloaded));
                    }
                }
                bout.close();
                in.close();
            }

        } catch (IOException ex) {
            new Error();
            ex.printStackTrace();
            return true;
        }
        return false;
    }
}