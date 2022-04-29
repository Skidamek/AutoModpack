package pl.skidam.automodpack;

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

public class SelfUpdater implements Runnable {

    String selfLink;
    File selfOut;

    public SelfUpdater() {
        this.selfLink = "https://github.com/Skidamek/AutoModpack/releases/download/pipel/AutoModpack.jar";;
        this.selfOut = new File( "./mods/AutoModpack.jar");;
    }

    boolean LatestVersion = false;

    @Override
    public void run() {

        Thread.currentThread().setName("AutoModpack - SelfUpdaterVersionCheck");
        Thread.currentThread().setPriority(10);

        // if latest mod is not same as current mod download new mod.
        // Check how big the mod file is
        File SelfUpdaterCheck = new File("./AutoModpack/SelfUpdaterVersionCheck.txt");
        if (SelfUpdaterCheck.exists()) {
            System.out.println("Checking if AutoModpack is up to date...");
            try {
                FileReader fr = new FileReader(SelfUpdaterCheck);
                Scanner inFile = new Scanner(fr);

                String line;

                // Read the first line from the file.
                line = inFile.nextLine();

                long currentSize = Long.parseLong(line);
                long latestSize = Long.parseLong(webfileSize(selfLink));

                if (currentSize != latestSize) {
                    System.out.println("Update found! Updating!");
                } else {
                    System.out.println("Didn't found any updates for AutoModpack!");
                    LatestVersion = true;
                }

                // Close the file.
                inFile.close();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        if (!LatestVersion || !SelfUpdaterCheck.exists()) {

            Thread.currentThread().setName("AutoModpack - SelfUpdater");
            Thread.currentThread().setPriority(10);


            File oldAM = new File("./AutoModpack/OldAutoModpack/");
            if (!oldAM.exists()) {
                oldAM.mkdir();
            }
            File selfOutFile = new File("./mods/AutoModpack.jar");
            if (selfOutFile.exists()) {
                try {
                    Files.copy(selfOut.toPath(), new File("./AutoModpack/OldAutoModpack/AutoModpack.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                System.out.println("LoL how did you get here? You should have the AutoModpack.jar in your mods folder.");
            }

            try {
                URL url = new URL(selfLink);
                HttpURLConnection http = (HttpURLConnection) url.openConnection();
                double fileSize = (double) http.getContentLengthLong();
                BufferedInputStream in = new BufferedInputStream(http.getInputStream());
                FileOutputStream fos = new FileOutputStream(selfOut);
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

                Files.copy(selfOut.toPath(), new File("./AutoModpack/AutoModpack.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);

                System.out.println("Successfully self updated!");

                // Write the AutoModpack file size to a file
                String AutoModpack = (selfOut.toPath().toString());
                printFileSizeNIO(AutoModpack);

            } catch (IOException ex) {
                System.out.println("Failed to update myself!");
                ex.printStackTrace();
            }
        }
    }

    private String webfileSize(String selfLink) {
        String size = "";
        try {
            URL url = new URL(selfLink);
            URLConnection conn = url.openConnection();
            size = conn.getHeaderField("Content-Length");
        } catch (IOException e) {
            e.printStackTrace();
        }

        return size;
    }

    private void printFileSizeNIO(String AutoModpack) {
        Path path = Paths.get(AutoModpack);

        try (FileWriter writer = new FileWriter("./AutoModpack/SelfUpdaterVersionCheck.txt")) {
            long bytes = Files.size(path);
            writer.write(String.format("%d", bytes));
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
