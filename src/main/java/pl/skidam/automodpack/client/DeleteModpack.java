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

public class DeleteModpack {
    private static boolean deleted;
    private static final File unzippedModpack = new File("./AutoModpack/modpack/");
    private static final List<File> modpackFiles = new ArrayList<>();

    public DeleteModpack() {
        System.out.println("Deleting modpack...");

        try {
            new UnZipper(out, unzippedModpack, "none");
        } catch (IOException e) {
            System.out.println("Error while unzipping!\n" + e);
            e.printStackTrace();
        }

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
                    path = new File(modpackFile + "\\" + child.getName() + "\\");
                }
                deleteLogic(path);
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
                    new Zipper(new File("./AutoModpack/TrashMod/"), file);
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
            } else if (file.exists() && file.length() == 16988) {
                System.out.println("Successfully trashed: " + file);
            } else {
                System.out.println("Failed to delete: " + file);
                deleted = false;
            }
        }
    }
}