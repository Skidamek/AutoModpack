package pl.skidam.automodpack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class OldConvertToNew implements Runnable {
    @Override
    public void run() {

        File AMdir = new File("./mods/downloads");
        if (AMdir.exists()) {
            try {
                Files.move(AMdir.toPath(), new File("./AutoModpack/" ).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        File AMzip = new File("./mods/downloads/AutoModpack.zip");
        if (AMzip.exists()) {
            AMzip.renameTo(new File("./AutoModpack/modpack.zip"));
        }
        File AMdowjar = new File("./mods/downloads/OldAutoModpack/OldAutoModpack.jar");
        if (AMdowjar.exists()) {
            AMzip.renameTo(new File("./AutoModpack/OldAutoModpack/AutoModpack.jar"));
        }

        File AMjar = new File("./AutoModpack/OldAutoModpack/OldAutoModpack.jar");
        if (AMjar.exists()) {
            AMzip.renameTo(new File("./AutoModpack/OldAutoModpack/AutoModpack.jar"));
        }

        try {
            Files.deleteIfExists(AMdir.toPath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
