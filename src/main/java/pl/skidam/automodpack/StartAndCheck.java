package pl.skidam.automodpack;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.modpack.CheckModpack;
import pl.skidam.automodpack.utils.Wait;

import static pl.skidam.automodpack.AutoModpackClient.*;

public class StartAndCheck {

    public StartAndCheck(boolean isLoading) {
//        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        // If minecraft is still loading wait for it to finish
        if (isLoading) {
            while (true) {
                String CurrentScreen = String.valueOf(MinecraftClient.getInstance().currentScreen);
                if (CurrentScreen.contains("net.minecraft.client.gui.screen")) {
                    break;
                }
                Wait.wait(50);
            }
            // wait to bypass most of the bugs
            Wait.wait(5000);
        }
        new SelfUpdater();
        new CheckModpack();

        // Checking loop
        AutoModpackClient.Checking = true;
        while (true) {
            if (AutoModpackUpdated != null && ModpackUpdated != null) {
                AutoModpackClient.Checking = false;
                new Finished();
                break;
            }
            Wait.wait(20);
        }
    }
}
