package pl.skidam.automodpack.modpack;

import pl.skidam.automodpack.AutoModpackClient;
import pl.skidam.automodpack.utils.Download;

import static pl.skidam.automodpack.AutoModpackClient.*;

public class DownloadModpack {

    public DownloadModpack() {

        AutoModpackClient.LOGGER.info("Downloading Modpack...");

        // Download and check if download is successful *magic*

        if (!Download.Download(link, out)) {
            AutoModpackClient.LOGGER.info("Failed downloaded modpack!");
            return;
        }

        AutoModpackClient.LOGGER.info("Successfully downloaded modpack!");

        new UnZip(out, "true");
    }
}
