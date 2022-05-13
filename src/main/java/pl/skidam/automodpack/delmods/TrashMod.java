package pl.skidam.automodpack.delmods;

import pl.skidam.automodpack.AutoModpack;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;

public class TrashMod implements Runnable {

    String URL = "https://github.com/Skidamek/TrashMod/releases/download/latest/trash.jar";
    File out = new File("./AutoModpack/TrashMod.jar");

    @Override
    public void run() {

        Thread.currentThread().setPriority(10);


        if (out.exists()) {
            return;
        }

        // Internet connection check
        while (true) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("https://www.google.com").openConnection();
                connection.setRequestMethod("HEAD");
                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new Exception("AutoModpack -- Internet isn't available, Failed to get code 200 from " + connection.getURL().toString());
                } else {
                    AutoModpack.LOGGER.info("Internet is available!");
                    break;
                }
            } catch (Exception e) {
                AutoModpack.LOGGER.warn("Make sure that you have an internet connection!");
            }
            wait(1000);
        }

        AutoModpack.LOGGER.info("Downloading TrashMod!");
        try {
            URL url = new URL(URL);
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            double fileSize = (double) http.getContentLengthLong();
            BufferedInputStream in = new BufferedInputStream(http.getInputStream());
            FileOutputStream fos = new FileOutputStream(out);
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

            AutoModpack.LOGGER.info("Successfully downloaded TrashMod!");

        } catch (IOException ex) {
            AutoModpack.LOGGER.error("Failed to download TrashMod!");
            ex.printStackTrace();
        }
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
