package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.client.modpack.CheckModpack;
import pl.skidam.automodpack.utils.LoadModpackLink;
import pl.skidam.automodpack.utils.Wait;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class StartAndCheck {
    public static boolean isChecking = false;
    public StartAndCheck(boolean isLoading, boolean onlyModpack) {

        new Thread(() -> {

            new LoadModpackLink();

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
                        break;
                    }
                    new Wait(25);
                }
            });

            AutoModpackToast.add(0);

            if (onlyModpack) {
                AutoModpackUpdated = "false";
                new CheckModpack(false);
            }

            if (!onlyModpack) {
                new CheckModpack(false);
                while (true) {
                    if (ModpackUpdated != null) {
                        new SelfUpdater(false);
                        break;
                    }
                    new Wait(25);
                }
            }
        }).start();
    }
}
