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

        CheckModpack();

    }


    public void CheckModpack() {

        Thread.currentThread().setName("AutoModpack - ModpackVersionCheck");
        Thread.currentThread().setPriority(10);

        // if latest modpack is not same as current modpack download new mods.
        // Check how big the Modpack file is
        File ModpackCheck = new File("./AutoModpack/ModpackVersionCheck.txt");
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
                } else if (latestSize == 0) {
                    Error();
                } else {
                    System.out.println("Didn't found any updates for modpack!");
                    LatestVersion = true;
                }

                // Close the file.
                inFile.close();

            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }

            if (!LatestVersion) {
                DownloadModpack();
            } else {
                After();
            }

        }
    }

    public void DownloadModpack() {

        //If the file don't exist, skip the check and download the Modpack

        Thread.currentThread().setName("AutoModpack - Downloader");
        Thread.currentThread().setPriority(10);

        // delay for 5 seconds
        try {
            Thread.sleep(5000);

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
                Error();
                ex.printStackTrace();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        After();
    }

    public void After() {

        // new Thread() in another Thread() doesn't work so well, so we use this
        // TODO make functions instanced of Threads for example Download();
        // Repeat this function every restart if modpack is up-to-date
        if (!Error) {

            // unzip
            Thread.currentThread().setName("AutoModpack - UnZip");
            Thread.currentThread().setPriority(10);

            // Start unzip
            System.out.println("AutoModpack -- Unzipping!");


            File ModpackZip = new File(out.toPath().toString());
            if (ModpackZip.exists()) {
                try {
                    new ZipFile(out).extractAll("./");
                } catch (ZipException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }

                System.out.println("AutoModpack -- Successfully unzipped!");

                // delete old mods
                Thread.currentThread().setName("AutoModpack - DeleteOldMods");
                Thread.currentThread().setPriority(10);

                // Add old mods by txt file to delmods folder/list
                File oldModsTxt = new File("./delmods.txt");
                if (oldModsTxt.exists()) {
                    try {
                        FileReader fr = new FileReader(oldModsTxt);
                        Scanner inFile = new Scanner(fr);

                        String line;

                        // Read the first line from the file.
                        line = inFile.nextLine();

                        while (inFile.hasNextLine()) {
                            File DelMod = new File("./delmods/" + line);
                            if (!DelMod.exists()) {
                                DelMod.mkdir();
                            }
                            line = inFile.nextLine();
                        }

                        // Close the file.
                        inFile.close();

                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(e);
                    }

                    try {
                        FileUtils.forceDelete(oldModsTxt);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                // Delete old mods by deleting the folder
                File oldMods = new File("./delmods/");
                String[] oldModsList = oldMods.list();
                if (oldMods.exists()) {
                    assert oldModsList != null;
                    System.out.println("AutoModpack -- Deleting old mods");
                    for (String name : oldModsList) {
                        File oldMod = new File("./mods/" + name);
                        if (name.endsWith(".jar") && !name.equals("AutoModpack.jar") && oldMod.exists()) {
                            System.out.println("AutoModpack -- Deleting: " + name);
                            try {
                                Files.copy(oldMods.toPath(), new File("./mods/" + name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                                FileUtils.forceDelete(new File("./mods/" + name));
                                System.out.println("AutoModpack -- Successfully deleted: " + name);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    try {
                        FileUtils.forceDelete(oldMods);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }


                Thread.currentThread().setName("AutoModpack");
                Thread.currentThread().setPriority(10);

                System.out.println("AutoModpack -- Here you are!");
            } else {
                DownloadModpack();
            }
        }
    }


    private void Error() {
        System.out.println("Error! Download server may be down or AutoModpack is wrongly configured!");
        System.out.println("Error! Download server may be down or AutoModpack is wrongly configured!");
        System.out.println("Error! Download server may be down or AutoModpack is wrongly configured!");
        System.out.println("Error! Download server may be down or AutoModpack is wrongly configured!");
        System.out.println("Error! Download server may be down or AutoModpack is wrongly configured!");
        Error = true;
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

        try (FileWriter writer = new FileWriter("./AutoModpack/ModpackVersionCheck.txt")) {
            long bytes = Files.size(path);
            writer.write(String.format("%d", bytes));
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
