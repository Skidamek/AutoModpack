package pl.skidam.automodpack.utils;

import java.io.FileReader;
import java.util.Scanner;

import static pl.skidam.automodpack.AutoModpackClient.modpack_link;
import static pl.skidam.automodpack.AutoModpackMain.LOGGER;
import static pl.skidam.automodpack.AutoModpackMain.link;
import static pl.skidam.automodpack.utils.ValidateURL.ValidateURL;

public class LoadModpackLink {

    public LoadModpackLink() {

        if (link == null) {
            // Load saved link from ./AutoModpack/modpack-link.txt file
            String savedLink = "";
            try {
                FileReader fr = new FileReader(modpack_link);
                Scanner inFile = new Scanner(fr);
                if (inFile.hasNextLine()) {
                    savedLink = inFile.nextLine();
                }
                inFile.close();
            } catch (Exception e) { // ignore
            }

            if (!savedLink.equals("")) {
                if (ValidateURL(savedLink)) {
                    link = savedLink;
                    LOGGER.info("Loaded saved link to modpack: " + link);
                } else {
                    LOGGER.error("Saved link is not valid url or is not end with /modpack");
                }
            }
        }
    }
}
