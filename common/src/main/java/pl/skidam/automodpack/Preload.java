package pl.skidam.automodpack;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.utils.JarUtilities;
import pl.skidam.automodpack.utils.ModpackContentTools;
import pl.skidam.automodpack.utils.SetupFiles;

import java.io.File;
import java.nio.file.Path;

import static pl.skidam.automodpack.StaticVariables.*;

public class Preload {
    public static void onPreInitialize() {
        long start = System.currentTimeMillis();
        LOGGER.info("Prelaunching AutoModpack...");
        preload = true;

        modsPath = Path.of("./mods/");
        JAR_NAME = JarUtilities.getJarFileOfMod("automodpack"); // set as correct name
        automodpackJar = new File(modsPath + File.separator + JAR_NAME); // set as correct jar file

        long startTime = System.currentTimeMillis();
        clientConfig = ConfigTools.loadConfig(clientConfigFile, Config.ClientConfigFields.class); // load client config
        serverConfig = ConfigTools.loadConfig(serverConfigFile, Config.ServerConfigFields.class); // load server config
        LOGGER.info("Loaded config! took " + (System.currentTimeMillis() - startTime) + "ms");

        if (clientConfig.autoRelauncher) {
            ReLauncher.init(FabricLauncherBase.getLauncher().getClassPath(), FabricLoaderImpl.INSTANCE.getLaunchArguments(false));
        }

        new SetupFiles();
        new SelfUpdater();

        if (Platform.getEnvironmentType().equals("CLIENT")) {
            String selectedModpack = clientConfig.selectedModpack;
            if (selectedModpack != null && !selectedModpack.equals("")) {
                selectedModpackDir = ModpackContentTools.getModpackDir(selectedModpack);
                selectedModpackLink = ModpackContentTools.getModpackLink(selectedModpack);
                new ModpackUpdater(selectedModpackLink, selectedModpackDir);
            }
        }

        Platform.downloadDependencies();

        LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
    }
}
