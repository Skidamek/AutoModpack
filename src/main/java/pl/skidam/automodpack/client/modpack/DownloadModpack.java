package pl.skidam.automodpack.client.modpack;

import pl.skidam.automodpack.utils.Download;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class DownloadModpack {

    public DownloadModpack() {

        LOGGER.info("Downloading Modpack...");

        // Download and check if download is successful *magic*

        if (!Download.Download(link, out)) {
            LOGGER.info("Failed downloaded modpack!");
            return;
        }

        LOGGER.info("Successfully downloaded modpack!");

        new UnZip(out, "true");
    }
}
