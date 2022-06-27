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

            CompletableFuture.runAsync(() -> {
                // Checking loop
                while (true) {
                    isChecking = true;
                    if (AutoModpackUpdated != null && ModpackUpdated != null) {
                        new Finished();
                        isChecking = false;
                        break;
                    }
                    Wait.wait(1000);
                }
            });

            if (onlyModpack) {
                AutoModpackUpdated = "false";
                new CheckModpack();
            }

            if (!onlyModpack) {
                new CheckModpack();
                new SelfUpdater();
            }
        }).start();
    }
}
