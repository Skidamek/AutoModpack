package pl.skidam.automodpack.Modpack;

import java.io.*;

public class Modpack implements Runnable {

    String link;
    File out;

    public Modpack(String link, File out) {
        this.link = link;
        this.out = out;
    }

    @Override
    public void run() {

        // delay for 5 seconds
        wait(5000);

        new CheckModpack(link, out);

    }

    public static void wait(int ms)
    {
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
