package pl.skidam.automodpack.delmods;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;

public class TrashMod implements Runnable {

    String URL = "https://github.com/Skidamek/TrashMod/releases/download/latest/trash.jar";
    File out = new File("./AutoModpack/TrashMod.jar");

    @Override
    public void run() {

        if (out.exists()) { return; }

        System.out.println("AutoModpack -- TrashMod is running!");
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
                    System.out.println(percent + "%");
                    lastPercent = percent;

                    // if lastPercent == percent
                } else {
                    percent = (String.format("%.0f", percentDownloaded));
                }
            }
            bout.close();
            in.close();

            System.out.println("Successfully downloaded TrashMod!");

        } catch (IOException ex) {
            System.out.println("Failed to download TrashMod!");
            ex.printStackTrace();
        }
    }
}
