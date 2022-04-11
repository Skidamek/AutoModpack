package pl.skidam.automodpack;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class UnZip implements Runnable {
    @Override
    public void run() {

        // Unzip
        System.out.println("AutoModpack -- Unzipping!");
        try {
            new ZipFile("./mods/AutoModpack.zip").extractAll("./");
        } catch (ZipException e) {
            e.printStackTrace();
        }
        System.out.println("AutoModpack -- Successful unzipped!");


        // Delete unless zip
        System.out.println("AutoModpack -- Deliting temporary files!");
        try {
            FileUtils.delete(new File("./mods/AutoModpack.zip"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("AutoModpack -- Here you are!");
    }
}
