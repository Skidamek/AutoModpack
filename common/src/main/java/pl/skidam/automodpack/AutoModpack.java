package pl.skidam.automodpack;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.MinecraftVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.modpack.Commands;
import pl.skidam.automodpack.modpack.Modpack;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.utils.JarUtilities;
import pl.skidam.automodpack.utils.MinecraftUserName;
import pl.skidam.automodpack.utils.ModpackContentTools;
import pl.skidam.automodpack.utils.SetupFiles;

import java.io.File;
import java.nio.file.Path;

public class AutoModpack {
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");
    public static final String MOD_ID = "automodpack";
    public static String VERSION = JarUtilities.getModVersion("automodpack");
    public static String MC_VERSION = MinecraftVersion.CURRENT.getName();
    public static File automodpackJar;
    public static String JAR_NAME; // File name how automodpack jar is called, for example automodpack-1.19.x.jar
    public static final File automodpackDir = new File("./automodpack/");
    public static final File modpacksDir = new File(automodpackDir + File.separator + "modpacks");
    public static final File automodpackUpdateJar = new File(automodpackDir + File.separator + JAR_NAME); // old self backup variable
    public static final File clientConfigFile = new File(automodpackDir + File.separator + "automodpack-client.json");
    public static final File serverConfigFile = new File(automodpackDir + File.separator + "automodpack-server.json");
    public static boolean isClothConfig;
    public static boolean isModMenu;
    public static boolean isVelocity;
    public static Path modsPath;
    public static String ClientLink;
    public static boolean preload;
    public static File selectedModpackDir;
    public static String selectedModpackLink;
    public static Config.ServerConfigFields serverConfig;
    public static Config.ClientConfigFields clientConfig;
    public static void onInitialize() {
        preload = false;

        LOGGER.info("AutoModpack is running on " + Platform.getPlatformType() + "!");

        Commands.register();

        if (Platform.getEnvironmentType().equals("SERVER")) {
            if (serverConfig.generateModpackOnStart) {
                AutoModpack.LOGGER.info("Generating modpack...");
                Modpack.generate();
            }
            ModPackets.registerS2CPackets();

            ServerLifecycleEvents.SERVER_STARTED.register(server -> Modpack.Host.start());
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> Modpack.Host.stop());
        } else {
            MinecraftUserName.get(); // To save the username` to variable in MinecraftUserName class for later use
            ModPackets.registerC2SPackets();

            // To be sure that everything is saved before game is closed
//            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
//                ConfigTools.saveConfig(clientConfigFile, clientConfig);
//                ConfigTools.saveConfig(serverConfigFile, serverConfig);
//            });
        }
    }


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
        AutoModpack.LOGGER.info("Loaded config! took " + (System.currentTimeMillis() - startTime) + "ms");

        ReLauncher.init(FabricLauncherBase.getLauncher().getClassPath(), FabricLoaderImpl.INSTANCE.getLaunchArguments(false));

        new SetupFiles();
        new SelfUpdater();

        if (Platform.getEnvironmentType().equals("CLIENT")) {

            String selectedModpack = clientConfig.selectedModpack;
            if (selectedModpack != null && !selectedModpack.equals("")) {
                selectedModpackDir = ModpackContentTools.getModpackDir(selectedModpack);
                selectedModpackLink = ModpackContentTools.getModpackLink(selectedModpack);
                new ModpackUpdater(selectedModpackLink, selectedModpackDir, true);
            }

//            new DeleteTrashedMods();
            new CompatCheck();
//            new DeleteMods(true, "false");


        } else if (Platform.getEnvironmentType().equals("SERVER")) {

        }

        new CompatCheck();

        LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");

    }
}