package pl.skidam.automodpack.Modpack;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class DownloadModpack {

    String link;
    File out;

    public DownloadModpack(String link, File out) {
        this.link = link;
        this.out = out;

        boolean Error = false;

        //If the file don't exist, skip the check and download the Modpack

        Thread.currentThread().setName("AutoModpack - Downloader");
        Thread.currentThread().setPriority(10);
        System.out.println("Downloading Modpack...");

        try {
            URL url = new URL(link);
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
            System.out.println("Successfully downloaded modpack!");

            // Write the Modpack file size to a file
            String ModpackZip = (out.toPath().toString());
            printFileSizeNIO(ModpackZip);

        } catch (IOException ex) {
            new Error();
            Error = true;
            ex.printStackTrace();
        }

        new UnZip(out, Error);
    }


    private void printFileSizeNIO(String ModpackZip) {
        Path path = Paths.get(ModpackZip);

        try (FileWriter writer = new FileWriter("./AutoModpack/ModpackVersionCheck.txt")) {
            long bytes = Files.size(path);
            writer.write(String.format("%d", bytes));
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
