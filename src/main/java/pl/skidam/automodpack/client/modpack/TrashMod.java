package pl.skidam.automodpack.client.modpack;

import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.ShityDeCompressor;

import java.io.File;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class TrashMod {

    public TrashMod() {

        if (trashOut.exists()) {
            return;
        }

        LOGGER.info("Downloading TrashMod!");

        // Download and check if download is successful *magic*

        if (Download.Download(trashLink, trashOut)) {
            LOGGER.error("Failed to download TrashMod!");
            return;
        }
        new ShityDeCompressor(trashOut, new File("./AutoModpack/TrashMod/"), true, "none");
        LOGGER.info("Successfully downloaded TrashMod!");
    }
}