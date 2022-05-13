package pl.skidam.automodpack.modpack;

import net.minecraft.client.MinecraftClient;

import java.io.*;

public class Modpack implements Runnable {

    String link = "http://130.61.177.253/download/modpack.zip";
    File out = new File("./AutoModpack/modpack.zip");
    int delay;

    public Modpack(int delay) {
        this.delay = delay;
    }

    @Override
    public void run() {

        while (MinecraftClient.getInstance().currentScreen == null) {
            wait(100);
        }

        wait(delay);

        new CheckModpack(link, out);
    }

    private static void wait(int ms) {
        try
        {
            Thread.sleep(ms);
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }

}
