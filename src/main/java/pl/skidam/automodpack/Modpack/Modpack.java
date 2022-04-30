package pl.skidam.automodpack.Modpack;

import java.io.*;

public class Modpack implements Runnable {

    String link;
    File out;
    int delay;

    public Modpack(int delay) {
        this.delay = delay;
        this.link = "http://130.61.177.253/download/modpack.zip";
        this.out = new File("./AutoModpack/modpack.zip");
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
