package pl.skidam.automodpack_core;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.ModpackContent;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;

import java.nio.file.Path;
import java.util.ArrayList;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Server {

    // TODO Finish this class that it will be able to host the server without mod
    public static void main(String[] args) {
        hostContentModpackDir.toFile().mkdirs();

        serverConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFieldsV2.class);
        if (serverConfig != null) {
            serverConfig.syncedFiles = new ArrayList<>();
            serverConfig.validateSecrets = false;
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

        ModpackExecutor modpackExecutor = new ModpackExecutor();
        ModpackContent modpackContent = new ModpackContent(serverConfig.modpackName, Path.of("./").toAbsolutePath().normalize(), hostModpackDir, serverConfig.syncedFiles, serverConfig.allowEditsInFiles, serverConfig.forceCopyFilesToStandardLocation, modpackExecutor.getExecutor());
        boolean generated = modpackExecutor.generateNew(modpackContent);

        if (generated) {
            LOGGER.info("Modpack generated!");
        } else {
            LOGGER.error("Failed to generate modpack!");
        }

        modpackExecutor.stop();
    }
}
