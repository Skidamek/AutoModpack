package pl.skidam.automodpack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class OldConvertToNew implements Runnable {
    @Override
    public void run() {

        File AMdir = new File("./mods/downloads");
        File AMdirOut = new File("./AutoModpack/");
        if (AMdir.exists()) {
            try {
                Files.move(AMdir.toPath(), AMdirOut.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        File AMzip = new File("./mods/downloads/AutoModpack.zip");
        File AMzipOut = new File("./AutoModpack/modpack.zip");
        if (AMzip.exists()) {
            try {
                Files.move(AMzip.toPath(), AMzipOut.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        File AMdowjar = new File("./mods/downloads/OldAutoModpack/OldAutoModpack.jar");
        File AMdowOut = new File("./AutoModpack/OldAutoModpack/AutoModpack.jar");
        if (AMdowjar.exists()) {
            try {
                Files.move(AMdowjar.toPath(), AMdowOut.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        File AMjar = new File("./AutoModpack/OldAutoModpack/OldAutoModpack.jar");
        File AMjarOut = new File("./AutoModpack/OldAutoModpack/AutoModpack.jar");
        if (AMjar.exists()) {
            try {
                Files.move(AMjar.toPath(), AMjarOut.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            Files.deleteIfExists(AMdir.toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        File AMvercheck = new File("./AutoModpack/SelfUpdaterVersionCheck.txt");
        File Mvercheck = new File("./AutoModpack/ModpackVersionCheck.txt");

        try {
            Files.deleteIfExists(AMvercheck.toPath());
            Files.deleteIfExists(Mvercheck.toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
