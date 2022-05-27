package pl.skidam.automodpack.modpack;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import pl.skidam.automodpack.AutoModpackClient;
import pl.skidam.automodpack.delmods.DeleteMods;

import java.io.File;

public class UnZip {

    File out;
    String ModpackUpdated;


    public UnZip(File out, String ModpackUpdated) {
        this.out = out;
        this.ModpackUpdated = ModpackUpdated;

        // Repeat this function every restart if modpack is up-to-date

        File ModpackZip = new File(out.toPath().toString());
        if (ModpackZip.exists()) {

            // unzip
            Thread.currentThread().setPriority(10);

            // Start unzip
            AutoModpackClient.LOGGER.info("Unzipping!");

            try {
                new ZipFile(out).extractAll("./");
            } catch (ZipException e) {
                e.printStackTrace();
            }

            AutoModpackClient.LOGGER.info("Successfully unzipped!");

            // delete old mods
            new DeleteMods(false, ModpackUpdated);

        }
    }
}

