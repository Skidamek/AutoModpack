package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import pl.skidam.automodpack.utils.SetupFiles;

import java.io.File;

public class AutoModpackServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        System.out.println("Welcome to AutoModpack on Server!");

        // TODO generate configs for the server
        // TODO add chad integration to the server who when you join the server, it will download the mods and update the mods by ping the server -- networking
        // TODO kick player if they don't have the AutoModpack
        // TODO add commands to gen modpack etc.

        new SetupFiles();

        if (new File("./AutoModpack/modpack/").length() != 0) {
            try {
                new ZipFile("./AutoModpack/modpack.zip").extractAll("./AutoModpack/modpack/");
            } catch (ZipException e) {
                e.printStackTrace();
            }
        }
    }
}
