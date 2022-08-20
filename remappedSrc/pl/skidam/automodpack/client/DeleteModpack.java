package pl.skidam.automodpack.client;

import org.apache.commons.io.FileDeleteStrategy;

import pl.skidam.automodpack.utils.UnZipper;
import pl.skidam.automodpack.utils.Zipper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackClient.modpack_link;
import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.JarUtilities.correctName;

public class DeleteModpack {
    private static boolean deleted;
    private static final File unzippedModpack = new File("./AutoModpack/modpack/");
    private static final List<File> modpackFiles = new ArrayList<>();
    private static final List<File> modpackFilesChild = new ArrayList<>();

    public DeleteModpack() {
        System.out.println("Deleting modpack...");

        try {
            new UnZipper(out, unzippedModpack, "none");
        } catch (IOException e) {
            System.out.println("Error while unzipping!\n" + e);
            e.printStackTrace();
        }

        for (File file : Objects.requireNonNull(unzippedModpack.listFiles())) { // to dont delete q/fapi on modpack delete
            if (file.getName().equals("mods")) {
                for (File file1 : Objects.requireNonNull(file.listFiles())) {
                    if (file1.getName().startsWith("fabric-api-") || file1.getName().startsWith("qfapi-")) {
                        file1.delete();
                    }
                }
            }
        }

        start();

        Runtime.getRuntime().addShutdownHook(new Thread(DeleteModpack::start));
    }

    private static void start() {
        deleted = true;
        int tryCounter = 0;

        selectFilesToDelete();

        while (true) {
            if (!deleted && tryCounter < 10) {
                tryCounter++;
                System.out.println("Trying to delete modpack again... " + tryCounter);
                deleted = true;
                selectFilesToDelete();
            }
            if (!deleted && tryCounter == 10) {
                System.out.println("AUTOMODPACK -- ERROR - DELETING MODPACK FAILED!");
            }
            if (deleted) {
                System.out.println("Modpack deleted successfully!");
                break;
            }
        }

        // Delete unzipped modpack dir, modpack.zip and modpack-link.txt
        try {
            FileDeleteStrategy.FORCE.delete(unzippedModpack);
            FileDeleteStrategy.FORCE.delete(out);
            FileDeleteStrategy.FORCE.delete(modpack_link);
        } catch (Exception ignored) { }

        System.out.println("Restart your game!");
    }

    private static void selectFilesToDelete() {
        File[] files = unzippedModpack.listFiles();
        for (File file : Objects.requireNonNull(files)) {
            if (file.isDirectory()) {
                modpackFiles.add(new File("./" + file.getName() + "/"));
            } else {
                deleteLogic(file);
            }
        }

        for (File modpackFile : modpackFiles) {
            File[] children = modpackFile.listFiles();
            for (File child : Objects.requireNonNull(children)) {
                File path = new File(modpackFile + "\\" + child.getName());
                if (child.isDirectory()) {
                    modpackFilesChild.add(new File(path + "\\"));
                } else {
                    deleteLogic(path);
                }
            }
        }

        for (File modpackFileChild : modpackFilesChild) {
            File[] children = modpackFileChild.listFiles();
            for (File child : Objects.requireNonNull(children)) {
                File path = new File(modpackFileChild + "\\" + child.getName());
                if (child.isDirectory()) {
                    deleteLogic(new File(path + "\\"));
                } else {
                    deleteLogic(path);
                }
            }
        }
    }

    private static void deleteLogic(File file) {
        if (file.exists() && !file.getName().equals(correctName)) {
            System.out.println("Deleting: " + file);
            try {
                FileDeleteStrategy.FORCE.delete(file);
            } catch (IOException ignored) { }

            if (file.exists() && file.getName().endsWith(".jar")) { // If mod to delete still exists
                try {
                    File emptyFolder = new File("./AutoModpack/empty/");
                    if (!emptyFolder.exists()) {
                        emptyFolder.mkdir();
                    }
                    new Zipper(emptyFolder, file);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    FileWriter fw = new FileWriter("./AutoModpack/trashed-mods.txt", true);
                    fw.write(file.getName() + "\n");
                    fw.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (file.exists()) {
                try {
                    FileDeleteStrategy.FORCE.delete(file);
                } catch (IOException ignored) { }
            }

            if (!file.exists()) {
                System.out.println("Successfully deleted: " + file);
            } else if (file.exists() && file.length() == 22) {
                System.out.println("Successfully trashed: " + file);
            } else {
                System.out.println("Failed to delete: " + file);
                deleted = false;
            }
        }
    }
}