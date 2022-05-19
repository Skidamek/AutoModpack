package pl.skidam.automodpack;

import net.minecraft.client.MinecraftClient;
import pl.skidam.automodpack.modpack.CheckModpack;

import static pl.skidam.automodpack.AutoModpackClient.*;

public class StartAndCheck implements Runnable {

    public StartAndCheck(boolean isLoading) {

        Thread.currentThread().setPriority(10);

        // If minecraft is still loading wait for it to finish
        if (isLoading) {
            while (MinecraftClient.getInstance().currentScreen == null) {
                wait(20);
            }
            wait(5000);
        }
        else {
            new CheckModpack();
            new SelfUpdater();
        }

        // Checking loop
        AutoModpackClient.Checking = true;
        while (true) {
            if (AutoModpackUpdated != null && ModpackUpdated != null) {
                AutoModpackClient.Checking = false;
                new Finished();
                break;
            }
            wait(20);
        }
    }

    @Override
    public void run() {
    }

    private static void wait(int ms) {
        try {
            Thread.sleep(ms);
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
