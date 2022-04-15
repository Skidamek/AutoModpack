package pl.skidam.automodpack;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class SelfUpdater implements Runnable {


    String selfLink;
    File selfOut;

    public SelfUpdater(String selfLink, File selfOut) {
        this.selfLink = selfLink;
        this.selfOut = selfOut;
    }

    @Override
    public void run() {

//        System.out.println("AutoModpack -- Deleting old files...");
//        try {
//            FileUtils.delete(new File("./mods/AutoModpack.jar"));
//        } catch (IOException e) {
//            e.printStackTrace();
//        }

        // delay for 5 seconds
        try {
            TimeUnit.SECONDS.sleep(5);

            try {
                URL url = new URL(selfLink);
                HttpURLConnection http = (HttpURLConnection) url.openConnection();
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
                while ((read = in.read(buffer, 0, 1024)) >= 0) {
                    bout.write(buffer, 0, read);
                    downloaded += read;
                    percentDownloaded = (downloaded * 100) / fileSize;

                    // if lastPercent != percent
                    if (!Objects.equals(lastPercent, percent)) {
                        percent = (String.format("%.0f", percentDownloaded));
                        System.out.println("AutoModpack -- Self update " + percent + "%");
                        lastPercent = percent;

                        // if lastPercent == percent
                    } else {
                        percent = (String.format("%.0f", percentDownloaded));
                    }
                }
                bout.close();
                in.close();
                System.out.println("AutoModpack -- Successful slef updated!");

            } catch (IOException ex) {
                ex.printStackTrace();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
