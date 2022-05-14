package pl.skidam.automodpack.modpack;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.delmods.DeleteMods;

import java.io.File;

public class UnZip {

    File out;
    boolean Error;
    boolean ModpackUpdated;


    public UnZip(File out, boolean Error, boolean ModpackUpdated) {
        this.out = out;
        this.Error = Error;
        this.ModpackUpdated = ModpackUpdated;

        // Repeat this function every restart if modpack is up-to-date

        if (!Error) {
            File ModpackZip = new File(out.toPath().toString());
            if (ModpackZip.exists()) {

                // unzip
                Thread.currentThread().setPriority(10);

                // Start unzip
                AutoModpack.LOGGER.info("Unzipping!");

                try {
                    new ZipFile(out).extractAll("./");
                } catch (ZipException e) {
                    e.printStackTrace();
                }

                AutoModpack.LOGGER.info("Successfully unzipped!");

                // delete old mods
                new DeleteMods(false, ModpackUpdated);
            }
        }
    }
}

