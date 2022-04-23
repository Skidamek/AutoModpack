package pl.skidam.automodpack;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class Download implements Runnable {

    String link;
    File out;

    public Download(String link, File out) {
        this.link = link;
        this.out = out;
    }

    boolean Error = false;
    boolean LatestVersion = false;

    @Override
    public void run() {

        Thread.currentThread().setName("AutoModpack - ModpackVersionCheck");
        Thread.currentThread().setPriority(10);

        // Check how big the Modpack file is
        File ModpackCheck = new File("./mods/downloads/ModpackVersionCheck.txt/");
        if (ModpackCheck.exists()) {
            try {
                FileReader fr = new FileReader(ModpackCheck);
                Scanner inFile = new Scanner(fr);

                String line;

                // Read the first line from the file.
                line = inFile.nextLine();
//                System.out.println("ModpackCheck" + " " + line + " " + "bytes");
//                System.out.println("webfileSize" + " " + webfileSize(link) + " " + "bytes");
                int currentSize = Integer.parseInt(line);
                int latestSize = Integer.parseInt(webfileSize(link));
//                System.out.println("ModpackCheck" + " " + currentSize + " " + "bytes (int)");
//                System.out.println("webfileSize" + " " + latestSize + " " + "bytes (int)");
                if (currentSize != latestSize) {
                    System.out.println("Modpack file size is different from the one on the server. Downloading new one.");
                } else {
                    System.out.println("Modpack file size is same as from the one on the server. Skipping downloading Modpack.");
                    LatestVersion = true;
                }

                // Close the file.
                inFile.close();

            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        //If the file don't exist, skip the check and download the Modpack

        // delay for 5 seconds

        if (!LatestVersion || !ModpackCheck.exists()) {

            Thread.currentThread().setName("AutoModpack - Downloader");
            Thread.currentThread().setPriority(10);

            try {
                Thread.sleep(5000);

                try {
                    URL url = new URL(link);
                    HttpURLConnection http = (HttpURLConnection) url.openConnection();
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
                    String ModpackZip = ("./mods/downloads/AutoModpack.zip");
                    printFileSizeNIO(ModpackZip);

                } catch (IOException ex) {
                    System.out.println("Error downloading modpack! Download server may be down or AutoModpack is wrongly configured!");
                    System.out.println("Error downloading modpack! Download server may be down or AutoModpack is wrongly configured!");
                    System.out.println("Error downloading modpack! Download server may be down or AutoModpack is wrongly configured!");
                    System.out.println("Error downloading modpack! Download server may be down or AutoModpack is wrongly configured!");
                    System.out.println("Error downloading modpack! Download server may be down or AutoModpack is wrongly configured!");
                    Error = true;
                    ex.printStackTrace();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            // new Thread() in another Thread() doesn't work so well, so we use this

            if (!Error) {

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


                System.out.println("AutoModpack -- Successfully unzipped!");

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
                            System.out.println("AutoModpack -- Successfully deleted: " + name);
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
    }

    // GITHUB COPILOT, I LOVE YOU!!!
    private String webfileSize(String link) {
        String size = "";
        try {
            URL url = new URL(link);
            URLConnection conn = url.openConnection();
            size = conn.getHeaderField("Content-Length");
        } catch (IOException e) {
            e.printStackTrace();
        }

        return size;
    }

    private void printFileSizeNIO(String ModpackZip) {
        Path path = Paths.get(ModpackZip);

        try (FileWriter writer = new FileWriter("./mods/downloads/ModpackVersionCheck.txt")) {
            long bytes = Files.size(path);
            writer.write(String.format("%d", bytes));
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
