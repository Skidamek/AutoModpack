package pl.skidam.automodpack.client.modpack;

import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.UnZipper;

import java.io.File;
import java.io.IOException;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class TrashMod {

    public static File unZippedTrashDir = new File("./AutoModpack/TrashMod/");
    public TrashMod() {

        if (trashOut.exists()) {
            return;
        }

        LOGGER.info("Downloading TrashMod!");

        while (!(FileUtils.sizeOfDirectory(unZippedTrashDir) == 20458)) {
            // Download and check if download is successful *magic*
            if (Download.Download(trashLink, trashOut)) {
                LOGGER.error("Failed to download TrashMod!");
                return;
            }

            try {
                new UnZipper(trashOut, unZippedTrashDir, "none");
            } catch (IOException e) {
                LOGGER.error("Failed to unzip TrashMod!");
            }
        }

        LOGGER.info("Successfully downloaded TrashMod!");
    }
}