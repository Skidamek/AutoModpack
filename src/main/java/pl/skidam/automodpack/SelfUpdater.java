package pl.skidam.automodpack;

import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;

public class SelfUpdater implements Runnable {


    String selfLink;
    File selfOut;

    public SelfUpdater(String link, File out) {
        this.selfLink = link;
        this.selfOut = out;
    }

    @Override
    public void run() {

//        System.out.println("AutoModpack -- Deleting old files...");
//        try {
//            FileUtils.delete(new File("./mods/AutoModpack.jar"));
//        } catch (IOException e) {
//            e.printStackTrace();
//        }

        try {
            URL url = new URL(selfLink);
            HttpURLConnection http = (HttpURLConnection)url.openConnection();
            double fileSize = (double)http.getContentLengthLong();
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
                percentDownloaded = (downloaded*100)/fileSize;

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

        } catch(IOException ex) {
            ex.printStackTrace();
        }
    }

}
