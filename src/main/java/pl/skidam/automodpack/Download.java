package pl.skidam.automodpack;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;

public class Download implements Runnable {

    String link;
    File out;

    public Download(String link, File out) {
        this.link = link;
        this.out = out;
    }

    @Override
    public void run() {

        try {
            URL url = new URL(link);
            HttpURLConnection http = (HttpURLConnection)url.openConnection();
            double fileSize = (double)http.getContentLengthLong();
            BufferedInputStream in = new BufferedInputStream(http.getInputStream());
            FileOutputStream fos = new FileOutputStream(this.out);
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
                    System.out.println("Downloaded " + percent + "%");
                    lastPercent = percent;

                // if lastPercent == percent
                } else {
                    percent = (String.format("%.0f", percentDownloaded));
                }
            }
            bout.close();
            in.close();
            System.out.println("Successful downloaded!");
            new Thread(new UnZip()).start();

        } catch(IOException ex) {
            ex.printStackTrace();
        }
    }
}
