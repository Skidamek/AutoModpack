package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.client.modpack.CheckModpack;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class StartAndCheck {

    public StartAndCheck(boolean isLoading, boolean onlyModpack) {

        new Thread(() -> {
            // If minecraft is still loading wait for it to finish
            if (isLoading) {
                while (MinecraftClient.getInstance().currentScreen == null) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                // wait to bypass most of the bugs
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            CompletableFuture.runAsync(() -> {
                // Checking loop
                Checking = true;
                while (true) {
                    LOGGER.error("AutoModpack updated? " + AutoModpackUpdated);
                    LOGGER.error("Modpack updated? " + ModpackUpdated);
                    if (AutoModpackUpdated != null && ModpackUpdated != null) {
                        Checking = false;
                        new Finished();
                        break;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            if (onlyModpack) {
                AutoModpackUpdated = "false";
                new CheckModpack();
                return;
            }

            new CheckModpack();
            new SelfUpdater();
        }).start();
    }
}
