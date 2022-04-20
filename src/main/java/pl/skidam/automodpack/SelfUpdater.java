package pl.skidam.automodpack;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class SelfUpdater implements Runnable {

    String selfLink;
    File selfOut;

    public SelfUpdater(String selfLink, File selfOut) {
        this.selfLink = selfLink;
        this.selfOut = selfOut;
    }

    public static boolean isAlive = true;

    @Override
    public void run() {

        Thread.currentThread().setName("AutoModpack - SelfUpdater");
        Thread.currentThread().setPriority(10);


        File oldAM = new File("./mods/downloads/OldAutoModpack/");
        if (!oldAM.exists()) {
            oldAM.mkdir();
        }
        try {
            Files.copy(selfOut.toPath(), new File("./mods/downloads/OldAutoModpack/OldAutoModpack.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        // delay for 5 seconds
        try {
            Thread.sleep(5000);

            try {
                URL url = new URL(selfLink);
                HttpURLConnection http = (HttpURLConnection)url.openConnection();
                double fileSize = (double) http.getContentLengthLong();
                BufferedInputStream in = new BufferedInputStream(http.getInputStream());
                FileOutputStream fos = new FileOutputStream(this.selfOut);
                BufferedOutputStream bout = new BufferedOutputStream(fos, 1024);
                byte[] buffer = new byte[1024];
                double downloaded = 0.00;
                int read;
                double percentDownloaded;
                String lastPercent = null;
                String percent = null;

                while ((read = in.read(buffer, 0, 1024)) >= 0 ) {
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

                Files.copy(selfOut.toPath(), new File("./mods/downloads/AutoModpack.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);

                System.out.println("Successfully slef updated!");

                isAlive = false;


            } catch (IOException ex) {
                System.out.println("Failed to update myself!");
                ex.printStackTrace();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            System.out.println("Failed to update myself!");
            throw new RuntimeException(e);
        }
    }
}
