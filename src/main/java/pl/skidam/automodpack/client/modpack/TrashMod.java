package pl.skidam.automodpack.client.modpack;

import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.UnZipper;
import pl.skidam.automodpack.utils.WebFileSize;

import java.io.File;
import java.io.IOException;

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

        long fileSize = WebFileSize.webfileSize(trashLink);
        while (!(trashOut.length() == fileSize)) { }

        try {
            new UnZipper(trashOut, new File("./AutoModpack/TrashMod/"), "none");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        LOGGER.info("Successfully downloaded TrashMod!");
    }
}