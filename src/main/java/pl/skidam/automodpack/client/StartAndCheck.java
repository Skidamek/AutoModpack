package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.client.modpack.CheckModpack;
import pl.skidam.automodpack.utils.Wait;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class StartAndCheck {

    public static boolean isChecking = false;

    public StartAndCheck(boolean isLoading, boolean onlyModpack) {


        new Thread(() -> {
            if (isLoading) {
            // If minecraft is still loading wait for it to finish
                while (MinecraftClient.getInstance().currentScreen == null) {
                    Wait.wait(1000);
                }
                // wait to bypass most of the bugs
                Wait.wait(5000);
            }

            AutoModpackUpdated = null;
            ModpackUpdated = null;

            CompletableFuture.runAsync(() -> {
                // Checking loop
                while (true) {
                    isChecking = true;
                    if (AutoModpackUpdated != null && ModpackUpdated != null) {
                        if (AutoModpackUpdated == "true") {
                            if (ModpackUpdated == "true") {
                                AutoModpackToast.add(7); // both AutoModpack and Modpack found update
                            } else {
                                AutoModpackToast.add(8); // AutoModpack updated and Modpack NOT found update
                            }
                        }
                        if (AutoModpackUpdated == "false") {
                            if (ModpackUpdated == "false") {
                                AutoModpackToast.add(9); // both AutoModpack and Modpack NOT found update
                            } else {
                                AutoModpackToast.add(10); // Automodpack NOT updated and Modpack found update
                            }
                        }
                        new Finished();
                        break;
                    }
                    Wait.wait(500);
                }
                isChecking = false;
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
                    Wait.wait(50);
                }
            }
        }).start();
    }
}
