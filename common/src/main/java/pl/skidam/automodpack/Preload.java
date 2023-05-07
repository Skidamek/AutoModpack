package pl.skidam.automodpack;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ModpackUtils;
import pl.skidam.automodpack.config.Jsons;
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
        clientConfig = ConfigTools.loadConfig(clientConfigFile, Jsons.ClientConfigFields.class); // load client config
        serverConfig = ConfigTools.loadConfig(serverConfigFile, Jsons.ServerConfigFields.class); // load server config
        LOGGER.info("Loaded config! took " + (System.currentTimeMillis() - startTime) + "ms");

        if (clientConfig.autoRelauncher) {
            ReLauncher.init(FabricLauncherBase.getLauncher().getClassPath(), FabricLoaderImpl.INSTANCE.getLaunchArguments(false));
        }

        new SetupFiles();

        String workingDirectory = System.getProperty("user.dir");
        if (workingDirectory.contains("com.qcxr.qcxr")) {
            quest = true;
            LOGGER.info("QuestCraft detected!");
        } else {
            quest = false;
            new SelfUpdater();
            Platform.downloadDependencies();
        }

        if (Platform.getEnvironmentType().equals("CLIENT") && !quest) {
            String selectedModpack = clientConfig.selectedModpack;
            if (selectedModpack != null && !selectedModpack.equals("")) {
                selectedModpackDir = ModpackContentTools.getModpackDir(selectedModpack);
                selectedModpackLink = ModpackContentTools.getModpackLink(selectedModpack);
                Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(selectedModpackLink);
                new ModpackUpdater(serverModpackContent, selectedModpackLink, selectedModpackDir);
            }
        }

        LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
    }
}
