package pl.skidam.automodpack;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

public class UnZip implements Runnable {

    @Override
    public void run() {

        // Start unzip
        System.out.println("AutoModpack -- Unzipping!");

        try {
            new ZipFile("./mods/downloads/AutoModpack.zip").extractAll("./");
        } catch (ZipException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }


        System.out.println("AutoModpack -- Successful unzipped!");

        new Thread(new DeleteOldMods()).start();

    }
}
