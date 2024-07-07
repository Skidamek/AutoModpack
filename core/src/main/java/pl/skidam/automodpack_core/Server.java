package pl.skidam.automodpack_core;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.Modpack;
import pl.skidam.automodpack_core.modpack.ModpackContent;

import java.nio.file.Path;
import java.util.ArrayList;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Server {

    // TODO Finish this class that it will be able to host the server without mod
    public static void main(String[] args) {

        if (args.length < 1) {
            LOGGER.error("Modpack id not provided!");
            return;
        }

        String modpackDirStr = args[0];

        Path cwd = Path.of(System.getProperty("user.dir"));
        Path modpackDir = cwd.resolve("modpacks").resolve(modpackDirStr);

        modpackDir.toFile().mkdirs();

        hostModpackContentFile = modpackDir.resolve("automodpack-content.json");
        serverConfigFile = modpackDir.resolve("automodpack-server.json");
        serverCoreConfigFile = modpackDir.resolve("automodpack-core.json");

        serverConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFields.class);
        if (serverConfig != null) {
            serverConfig.syncedFiles = new ArrayList<>();
            ConfigTools.save(serverConfigFile, serverConfig);
        }

        Jsons.ServerCoreConfigFields serverCoreConfig = ConfigTools.load(serverCoreConfigFile, Jsons.ServerCoreConfigFields.class);
        if (serverCoreConfig != null) {
            AM_VERSION = serverCoreConfig.automodpackVersion;
            LOADER = serverCoreConfig.loader;
            LOADER_VERSION = serverCoreConfig.loaderVersion;
            MC_VERSION = serverCoreConfig.mcVersion;
            ConfigTools.save(serverCoreConfigFile, serverCoreConfig);
        }

        Modpack modpack = new Modpack();
        ModpackContent modpackContent = new ModpackContent(serverConfig.modpackName, null, modpackDir, serverConfig.syncedFiles, serverConfig.allowEditsInFiles, modpack.CREATION_EXECUTOR);
        boolean generated = modpack.generateNew(modpackContent);

        if (generated) {
            LOGGER.info("Modpack generated!");
        } else {
            LOGGER.error("Failed to generate modpack!");
        }

        modpack.CREATION_EXECUTOR.shutdownNow();
    }
}
