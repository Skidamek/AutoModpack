package pl.skidam.automodpack.delmods;

import pl.skidam.automodpack.AutoModpackClient;
import pl.skidam.automodpack.utils.Download;
import pl.skidam.automodpack.utils.InternetConnectionCheck;

import static pl.skidam.automodpack.AutoModpackClient.*;

public class TrashMod {

    public TrashMod() {

        if (trashOut.exists()) {
            return;
        }

        InternetConnectionCheck.InternetConnectionCheck();

        AutoModpackClient.LOGGER.info("Downloading TrashMod!");

        // Download and check if download is successful *magic*

        if (!Download.Download(trashLink, trashOut)) {
            AutoModpackClient.LOGGER.error("Failed to download TrashMod!");
            return;
        }
        AutoModpackClient.LOGGER.info("Successfully downloaded TrashMod!");
    }
}