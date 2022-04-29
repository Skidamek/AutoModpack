package pl.skidam.automodpack.Modpack;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.Scanner;

public class CheckModpack {

    String link;
    File out;

    public CheckModpack(String link, File out) {
        this.link = link;
        this.out = out;

        boolean Error = false;
        boolean LatestVersion = false;

        Thread.currentThread().setName("AutoModpack - ModpackVersionCheck");
        Thread.currentThread().setPriority(10);

        // if latest modpack is not same as current modpack download new mods.
        // Check how big the Modpack file is
        File ModpackCheck = new File("./AutoModpack/ModpackVersionCheck.txt");
        File Modpack = new File("./AutoModpack/modpack.zip");
        if (ModpackCheck.exists()) {
            System.out.println("Checking if modpack is up to date...");
            try {
                FileReader fr = new FileReader(ModpackCheck);
                Scanner inFile = new Scanner(fr);

                String line;

                // Read the first line from the file.
                line = inFile.nextLine();

                long currentSize = Long.parseLong(line);
                long latestSize = Long.parseLong(webfileSize(link));

                if (currentSize != latestSize) {
                    System.out.println("Update found! Downloading new mods!");
                    new DownloadModpack(link, out);
                } else if (latestSize == 0) {
                    new Error();
                    Error = true;
                } else {
                    if (Modpack.exists()) {
                        System.out.println("Didn't found any updates for modpack!");
                        LatestVersion = true;
                        new UnZip(out, Error);
                    } else {
                        new DownloadModpack(link, out);
                    }
                }

                // Close the file.
                inFile.close();

            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
//        } else if (!Modpack.exists()) {
//            new DownloadModpack(link, out);
        } else {
            System.out.println("Downloading new mods!");
            new DownloadModpack(link, out);
        }
    }


    // GITHUB COPILOT, I LOVE YOU!!!
    public String webfileSize(String link) {
        String size = "";
        try {
            URL url = new URL(link);
            URLConnection conn = url.openConnection();
            size = conn.getHeaderField("Content-Length");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return size;  // returns the size of the file in bytes
    }
}
