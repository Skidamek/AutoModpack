package pl.skidam.automodpack;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.modpack.CheckModpack;
import pl.skidam.automodpack.utils.Wait;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class StartAndCheck {

    public StartAndCheck(boolean isLoading) {

        Thread.currentThread().setName("AutoModpack");

        // If minecraft is still loading wait for it to finish
        if (isLoading) {
            while (true) {
                String CurrentScreen = String.valueOf(MinecraftClient.getInstance().currentScreen);
                if (CurrentScreen.contains("net.minecraft")) {
                    break;
                }
                Wait.wait(50);
            }
            // wait to bypass most of the bugs
            Wait.wait(5000);
        }

        // Checking loop
        new Thread(() -> {
            Thread.currentThread().setName("AutoModpack");
            Checking = true;
            while (true) {
                if (AutoModpackUpdated != null && ModpackUpdated != null) {
                    Checking = false;
                    new Finished();
                    break;
                }
                Wait.wait(20);
            }
        }).start();

        new SelfUpdater();
        new CheckModpack();
    }
}
