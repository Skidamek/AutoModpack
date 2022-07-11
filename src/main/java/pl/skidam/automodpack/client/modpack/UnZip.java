package pl.skidam.automodpack.client.modpack;

import pl.skidam.automodpack.utils.UnZipper;
import pl.skidam.automodpack.utils.Zipper;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class UnZip {

    public UnZip(File out, String ModpackUpdated) {

        // Repeat this function every restart if modpack is up-to-date
        if (out.exists()) {

            // Start unzip
            LOGGER.info("Unzipping!");
            new UnZipper(out, new File("./"), true, "none");
            LOGGER.info("Successfully unzipped!");

            // delete old mods
            new DeleteMods(false, ModpackUpdated);
        }
    }
}

