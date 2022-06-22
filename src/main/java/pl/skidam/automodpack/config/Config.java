package pl.skidam.automodpack.config;

import net.fabricmc.loader.api.FabricLoader;
import pl.skidam.automodpack.AutoModpackMain;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public class Config {

    public static boolean DANGER_SCREEN;
    public static boolean CHECK_UPDATES_BUTTON;
    public static boolean CLONE_MODS;
    public static boolean SYNC_MODS;
    public static int HOST_PORT;
    public static int HOST_THREAD_COUNT;
    public static String HOST_EXTERNAL_IP;
    public static String EXTERNAL_HOST_SERVER;

    static {
        final Properties properties = new Properties();
        final Path path = FabricLoader.getInstance().getConfigDir().resolve("automodpack.properties");
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path, StandardOpenOption.CREATE)) {
                properties.load(in);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        DANGER_SCREEN = getBoolean(properties, "danger_screen", true);
        CHECK_UPDATES_BUTTON = getBoolean(properties, "check_updates_button", true);
        CLONE_MODS = getBoolean(properties, "clone_mods", true);
        SYNC_MODS = getBoolean(properties, "sync_mods", false);
        HOST_PORT = getInt(properties, "host_port", 30037);
        HOST_THREAD_COUNT = getInt(properties, "host_thread_count", 2);
        HOST_EXTERNAL_IP = getString(properties, "host_external_ip", "");
        EXTERNAL_HOST_SERVER = getString(properties, "external_host_server", "");

        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(out, "Configuration file for AutoModpack");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void init() {
    }

    private static int getInt(Properties properties, String key, int def) {
        try {
            return Integer.parseUnsignedInt(properties.getProperty(key));
        } catch (NumberFormatException e) {
            AutoModpackMain.LOGGER.error("Invalid value for " + key + " in automodpack.properties. Value must be an integer. Value restarted to " + def);
            properties.setProperty(key, String.valueOf(def));
            return def;
        }
    }

    private static String getString(Properties properties, String key, String def) {
        return properties.getProperty(key);
    }

    private static boolean getBoolean(Properties properties, String key, boolean def) {
        String booleanValue = String.valueOf(Boolean.parseBoolean(properties.getProperty(key)));
        if (booleanValue.equals(properties.getProperty(key))) {
            return Boolean.parseBoolean(booleanValue);
        } else {
            AutoModpackMain.LOGGER.error("Invalid value for " + key + " in automodpack.properties. Value must be true or false. Value restarted to " + def);
            properties.setProperty(key, String.valueOf(def));
            return def;
        }
    }
}
