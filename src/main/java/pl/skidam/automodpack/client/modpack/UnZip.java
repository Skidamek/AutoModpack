package pl.skidam.automodpack.client.modpack;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.progress.ProgressMonitor;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class UnZip {

    public static ProgressMonitor progressMonitor;

    public UnZip(File out, String ModpackUpdated) {

        // Repeat this function every restart if modpack is up-to-date
        if (out.exists()) {

            // Start unzip
            LOGGER.info("Unzipping!");

            ZipFile modpackZip = new ZipFile(out);
            modpackZip.setRunInThread(true);
            progressMonitor = modpackZip.getProgressMonitor();

            try {
                modpackZip.extractAll("./");
            } catch (ZipException e) {
                LOGGER.error("Error while unzipping modpack!");
                LOGGER.error(e.getMessage());
            }

            LOGGER.info("Successfully unzipped!");

            // delete old mods
            new DeleteMods(false, ModpackUpdated);
        }
    }
}

