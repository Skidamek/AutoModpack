package pl.skidam.automodpack.utils;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackServer.changelogsDir;
import static pl.skidam.automodpack.utils.JarUtilities.selfOut;

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

        File blacklistModsTxt = new File("./AutoModpack/blacklistMods.txt");
        if (!blacklistModsTxt.exists()) {
            try {
                blacklistModsTxt.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        File modsDir = new File("./AutoModpack/modpack/mods/");
        if (!modsDir.exists()) {
            modsDir.mkdir();
        }

        File confDir = new File("./AutoModpack/modpack/config/");
        if (!confDir.exists()) {
            confDir.mkdir();
        }

        File shaderpacksDir = new File("./AutoModpack/modpack/shaderpacks/");
        if (!shaderpacksDir.exists()) {
            shaderpacksDir.mkdir();
        }

        File resourcepacksDir = new File("./AutoModpack/modpack/resourcepacks/");
        if (!resourcepacksDir.exists()) {
            resourcepacksDir.mkdir();
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

        File modpack_link = new File("./AutoModpack/modpack-link.txt");

        try {
            if (!modpack_link.exists()) {
                modpack_link.createNewFile();
            }
        } catch (IOException e) { // ignore
        }

        File modpack_list = new File("./AutoModpack/modpacks/");
        if (!modpack_list.exists()) {
            modpack_list.mkdir();
        }

        try {
            FileDeleteStrategy.FORCE.delete(new File("./AutoModpack/modpack/"));
        } catch (IOException e) { // ignore
        }

        // delete
        File trashmodFolder = new File("./AutoModpack/TrashMod/");
        if (trashmodFolder.exists()) {
            FileUtils.deleteQuietly(trashmodFolder);
        }

        File emptyFolder = new File("./AutoModpack/empty/");
        if (emptyFolder.exists()) {
            FileUtils.deleteQuietly(emptyFolder);
        }

        File TrashMod = new File("./AutoModpack/TrashMod.jar");
        if (TrashMod.exists()) {
            FileUtils.deleteQuietly(TrashMod);
        }

        // extract icon
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            try {
                new UnZipper(selfOut, new File("./AutoModpack/"), "assets/automodpack/icon.png");
                FileUtils.copyFileToDirectory(new File("./AutoModpack/assets/automodpack/icon.png"), new File("./AutoModpack/"));
                FileUtils.deleteQuietly(new File("./AutoModpack/assets/"));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}