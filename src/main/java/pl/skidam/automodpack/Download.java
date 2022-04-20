package pl.skidam.automodpack;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class Download implements Runnable {

    String link;
    File out;

    public Download(String link, File out) {
        this.link = link;
        this.out = out;
    }

    @Override
    public void run() {

        Thread.currentThread().setName("AutoModpack - Downloader");
        Thread.currentThread().setPriority(10);

        // delay for 5 seconds
        try {
            Thread.sleep(5000);

            try {
                URL url = new URL(link);
                HttpURLConnection http = (HttpURLConnection)url.openConnection();
                double fileSize = (double) http.getContentLengthLong();
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

            } catch(IOException ex) {
                System.out.println("Error downloading modpack!");
                ex.printStackTrace();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        // new Thread() in another Thread() doesn't work so well, so we use this

        // unzip

        Thread.currentThread().setName("AutoModpack - UnZip");
        Thread.currentThread().setPriority(10);

        // Start unzip
        System.out.println("AutoModpack -- Unzipping!");

        try {
            new ZipFile("./mods/downloads/AutoModpack.zip").extractAll("./");
        } catch (ZipException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }


        System.out.println("AutoModpack -- Successful unzipped!");

        // delete old mods

        Thread.currentThread().setName("AutoModpack - DeleteOldMods");
        Thread.currentThread().setPriority(10);

        System.out.println("AutoModpack -- Deleting old mods");

        File oldMods = new File("./delmods/");
        String[] oldModsList = oldMods.list();
        if (oldMods.exists()) {
            for (String name : oldModsList) {
                System.out.println("AutoModpack -- Deleting: " + name);
                try {
                    Files.copy(oldMods.toPath(), new File("./mods/" + name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    FileUtils.forceDelete(new File("./mods/" + name));
                    System.out.println("AutoModpack -- Successful deleted: " + name);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            try {
                FileUtils.forceDelete(oldMods);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }

        System.out.println("AutoModpack -- Here you are!");


        // Delete unless zip
//        System.out.println("AutoModpack -- Deliting temporary files!");
//        try {
//            FileUtils.delete(new File("./mods/downloads/AutoModpack.zip"));
//        } catch (IOException e) {
//            e.printStackTrace();
//        }

    }
}
