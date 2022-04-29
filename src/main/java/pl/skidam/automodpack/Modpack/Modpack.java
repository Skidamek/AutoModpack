package pl.skidam.automodpack.Modpack;

import java.io.*;

public class Modpack implements Runnable {

    String link;
    File out;

    public Modpack() {
        this.link = "http://130.61.177.253/download/modpack.zip";
        this.out = new File("./AutoModpack/modpack.zip");
    }

    @Override
    public void run() {

        new CheckModpack(link, out);

    }
}
