package pl.skidam.automodpack.modpack;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import pl.skidam.automodpack.delmods.DeleteMods;

import java.io.File;

public class UnZip {

    File out;
    boolean Error;

    public UnZip(File out, boolean Error) {
        this.out = out;
        this.Error = Error;

        // Repeat this function every restart if modpack is up-to-date

        if (!Error) {
            File ModpackZip = new File(out.toPath().toString());
            if (ModpackZip.exists()) {

                // unzip
                Thread.currentThread().setName("AutoModpack - UnZip");
                Thread.currentThread().setPriority(10);

                // Start unzip
                System.out.println("AutoModpack -- Unzipping!");

                try {
                    new ZipFile(out).extractAll("./");
                } catch (ZipException e) {
                    e.printStackTrace();
                }

                System.out.println("AutoModpack -- Successfully unzipped!");

                // delete old mods
                new DeleteMods();
            }
        }
    }
}

