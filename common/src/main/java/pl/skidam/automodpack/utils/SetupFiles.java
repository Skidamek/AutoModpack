package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.Platform;

import java.io.File;

public class SetupFiles {
    public SetupFiles() {

        File AMdir = new File("./automodpack/");
        // Check if AutoModpack path exists
        if (!AMdir.exists()) {
            AMdir.mkdirs();
        }

        if (Platform.getEnvironmentType().equals("SERVER")) {
            server();
        }

        if (Platform.getEnvironmentType().equals("CLIENT")) {
            client();
        }
    }
    private void server() {

//        File modpackDir = new File("./automodpack/modpack/");
//        if (!modpackDir.exists()) {
//            modpackDir.mkdirs();
//        }
//
//        File blacklistModsTxt = new File("./automodpack/blacklistMods.txt");
//        if (!blacklistModsTxt.exists()) {
//            try {
//                blacklistModsTxt.createNewFile();
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//
//        File modsDir = new File("./automodpack/modpack/mods/");
//        if (!modsDir.exists()) {
//            modsDir.mkdirs();
//        }
//
//        File confDir = new File("./automodpack/modpack/config/");
//        if (!confDir.exists()) {
//            confDir.mkdirs();
//        }
//
//        File shaderpacksDir = new File("./automodpack/modpack/shaderpacks/");
//        if (!shaderpacksDir.exists()) {
//            shaderpacksDir.mkdirs();
//        }
//
//        File resourcepacksDir = new File("./automodpack/modpack/resourcepacks/");
//        if (!resourcepacksDir.exists()) {
//            resourcepacksDir.mkdirs();
//        }

//        File delFile = new File("./automodpack/modpack/delmods.txt");
//        if (!delFile.exists()) {
//            try {
//                delFile.createNewFile();
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }

//        File tempDir = new File("./automodpack/temp/");
//        if (!tempDir.exists()) {
//            tempDir.mkdir();
//        }
    }

    private void client() {

        File modpacks = new File("./automodpack/modpacks/");
        if (!modpacks.exists()) {
            modpacks.mkdirs();
        }
    }
}