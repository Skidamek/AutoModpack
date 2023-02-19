package pl.skidam.automodpack;

import net.minecraft.MinecraftVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.utils.JarUtilities;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class StaticVariables {
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
    public static final Set<String> keyWordsOfDisconnect = new HashSet<>(Arrays.asList("install", "update", "download", "handshake", "incompatible", "outdated", "client", "version"));
    public static Path modsPath;
    public static String ClientLink;
    public static boolean preload;
    public static File selectedModpackDir;
    public static String selectedModpackLink;
    public static Config.ServerConfigFields serverConfig;
    public static Config.ClientConfigFields clientConfig;
}
