package pl.skidam.automodpack.client;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.client.modpack.CheckModpack;
import pl.skidam.automodpack.utils.Wait;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class StartAndCheck {

    public StartAndCheck(boolean isLoading, boolean onlyModpack) {

        // If minecraft is still loading wait for it to finish
        if (isLoading) {
            while (MinecraftClient.getInstance().currentScreen == null) {
                Wait.wait(1000);
            }
            // wait to bypass most of the bugs
            Wait.wait(5000);
        }

        CompletableFuture.runAsync(() -> {
            // Checking loop
            Checking = true;
            while (true) {
                if (AutoModpackUpdated != null && ModpackUpdated != null) {
                    Checking = false;
                    new Finished();
                    break;
                }
                Wait.wait(1000);
            }
        });

        if (onlyModpack) {
            AutoModpackUpdated = "false";
            new CheckModpack();
            return;
        }

        new SelfUpdater();
        new SelfUpdater();
    }
}
