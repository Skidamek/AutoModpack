package pl.skidam.automodpack.utils;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.AutoModpackServer.changelogsDir;

public class SetupFiles {
    public SetupFiles() {

        File AMdir = new File("./AutoModpack/");
        // Check if AutoModpack path exists
        if (!AMdir.exists()) {
            AMdir.mkdir();
        }

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            server();
        }

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            client();
        }
    }
    private void server() {

        File modpackDir = new File("./AutoModpack/modpack/");
        if (!modpackDir.exists()) {
            modpackDir.mkdir();
        }

        File modsDir = new File("./AutoModpack/modpack/mods/");
        if (!modsDir.exists()) {
            modsDir.mkdir();
        }

        File confDir = new File("./AutoModpack/modpack/config/");
        if (!confDir.exists()) {
            confDir.mkdir();
        }

        File delFile = new File("./AutoModpack/modpack/delmods.txt");
        if (!delFile.exists()) {
            try {
                delFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        File modpackClientModsDir = new File("./AutoModpack/modpack/[CLIENT] mods");
        if (!modpackClientModsDir.exists()) {
            modpackClientModsDir.mkdir();
        }

        if (!changelogsDir.exists()) {
            changelogsDir.mkdir();
        }

//        File tempDir = new File("./AutoModpack/temp/");
//        if (!tempDir.exists()) {
//            tempDir.mkdir();
//        }
    }

    private void client() {

        File[] files = new File("./AutoModpack/").listFiles();
        for (File file : Objects.requireNonNull(files)) {
            if (file.getName().contains("automodpack")) {
                FileUtils.deleteQuietly(file);
            }
        }

        File modpackZip = new File("./AutoModpack/modpack.zip");
        if (modpackZip.exists()) {
            FileUtils.deleteQuietly(modpackZip);
        }

        if (!new File("./AutoModpack/TrashMod/").exists() && trashOut.exists()) {
            try {
                new UnZipper(trashOut, new File("./AutoModpack/TrashMod/"), "none");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        File modpack_link = new File ("./AutoModpack/modpack-link.txt");

        try {
            if (!modpack_link.exists()) {
                modpack_link.createNewFile();
            }
        } catch (IOException e) { // ignore
        }

        File modpack_list = new File ("./AutoModpack/modpacks/");
        if (!modpack_list.exists()) {
            modpack_list.mkdir();
        }

        try {
            FileDeleteStrategy.FORCE.delete(new File("./AutoModpack/modpack/"));
        } catch (IOException e) { // ignore
        }
    }
}