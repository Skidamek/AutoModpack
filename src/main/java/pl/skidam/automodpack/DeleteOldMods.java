package pl.skidam.automodpack;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class DeleteOldMods implements Runnable {

    public void run() {
        System.out.println("AutoModpack -- Deleting old mods");

        // Delete old mods
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
        }

        System.out.println("AutoModpack -- Here you are!");

    }
}
