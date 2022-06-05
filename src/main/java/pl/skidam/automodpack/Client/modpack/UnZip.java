package pl.skidam.automodpack.Client.modpack;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class UnZip {

    public UnZip(File out, String ModpackUpdated) {

        // Repeat this function every restart if modpack is up-to-date
        if (out.exists()) {

            // Start unzip
            LOGGER.info("Unzipping!");

            try {
                new ZipFile(out).extractAll("./");
            } catch (ZipException e) {
                e.printStackTrace();
            }

            LOGGER.info("Successfully unzipped!");

            // delete old mods
            new DeleteMods(false, ModpackUpdated);
        }
    }
}

