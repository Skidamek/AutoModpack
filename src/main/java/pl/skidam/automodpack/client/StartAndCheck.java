package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.client.modpack.CheckModpack;
import pl.skidam.automodpack.utils.Wait;

import java.io.FileReader;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackClient.modpack_link;
import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.ValidateURL.ValidateURL;

public class StartAndCheck {

    public static boolean isChecking = false;
    public StartAndCheck(boolean isLoading, boolean onlyModpack) {

        new Thread(() -> {

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

            if (isLoading) {
                // If minecraft is still loading wait for it to finish
                while (MinecraftClient.getInstance().currentScreen == null) {
                    new Wait(1000);
                }
                // Wait to bypass most of the bugs
                new Wait(5000);
            }

            AutoModpackUpdated = null;
            ModpackUpdated = null;

            CompletableFuture.runAsync(() -> {
                // Checking loop
                while (true) {
                    isChecking = true;
                    if (AutoModpackUpdated != null && ModpackUpdated != null) {
                        new Finished();
                        isChecking = false;
                        break;
                    }
                    new Wait(500);
                }
            });

            if (onlyModpack) {
                AutoModpackUpdated = "false";
                new CheckModpack();
            }

            if (!onlyModpack) {
                new CheckModpack();
                while (true) {
                    if (ModpackUpdated != null) {
                        new SelfUpdater(false);
                        break;
                    }
                    new Wait(50);
                }
            }
        }).start();
    }
}
