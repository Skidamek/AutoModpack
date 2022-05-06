package pl.skidam.automodpack.modpack;

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
