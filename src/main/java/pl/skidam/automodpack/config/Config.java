package pl.skidam.automodpack.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class Config {
    public static boolean DANGER_SCREEN;
    public static boolean CHECK_UPDATES_BUTTON;
    public static boolean DELETE_MODPACK_BUTTON;
    public static boolean MODPACK_HOST;
    public static boolean SYNC_MODS;
    public static boolean ONLY_OPTIONAL_MODPACK;
    public static int HOST_PORT;
    public static int HOST_THREAD_COUNT;
    public static String HOST_EXTERNAL_IP;
    public static String EXTERNAL_MODPACK_HOST;

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
        DELETE_MODPACK_BUTTON = getBoolean(properties, "delete_modpack_button", true);
        MODPACK_HOST = getBoolean(properties, "modpack_host", true);
        SYNC_MODS = getBoolean(properties, "sync_mods", false);
        ONLY_OPTIONAL_MODPACK = getBoolean(properties, "optional_modpack", false);
        HOST_PORT = getInt(properties, "host_port", 30037);
        HOST_THREAD_COUNT = getInt(properties, "host_thread_count", 2);
        HOST_EXTERNAL_IP = getString(properties, "host_external_ip", "");
        EXTERNAL_MODPACK_HOST = getString(properties, "external_modpack_host", "");

        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(out, "Configuration file for AutoModpack");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void init() {
    }

    public void save() {
        LOGGER.info("Saving AutoModpack config...");

        final Properties properties = new Properties();
        final Path path = FabricLoader.getInstance().getConfigDir().resolve("automodpack.properties");

        properties.setProperty("danger_screen", String.valueOf(DANGER_SCREEN));
        properties.setProperty("check_updates_button", String.valueOf(CHECK_UPDATES_BUTTON));
        properties.setProperty("delete_modpack_button", String.valueOf(DELETE_MODPACK_BUTTON));
        properties.setProperty("modpack_host", String.valueOf(MODPACK_HOST));
        properties.setProperty("sync_mods", String.valueOf(SYNC_MODS));
        properties.setProperty("optional_modpack", String.valueOf(ONLY_OPTIONAL_MODPACK));
        properties.setProperty("host_port", String.valueOf(HOST_PORT));
        properties.setProperty("host_thread_count", String.valueOf(HOST_THREAD_COUNT));
        properties.setProperty("host_external_ip", HOST_EXTERNAL_IP);
        properties.setProperty("external_modpack_host", EXTERNAL_MODPACK_HOST);

        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(out, "Configuration file for AutoModpack");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private static int getInt(Properties properties, String key, int def) {
        try {
            return Integer.parseUnsignedInt(properties.getProperty(key));
        } catch (NumberFormatException e) {
            if (Files.isRegularFile(FabricLoader.getInstance().getConfigDir().resolve("automodpack.properties"))) {
                LOGGER.error("Invalid value for " + key + " in automodpack.properties. Value must be an integer. Value restarted to " + def);
            }
            properties.setProperty(key, String.valueOf(def));
            return def;
        }
    }

    private static String getString(Properties properties, String key, String def) {
        if (properties.getProperty(key) == null) {
            properties.setProperty(key, def);
            return def;
        }
        return properties.getProperty(key);
    }

    private static boolean getBoolean(Properties properties, String key, boolean def) {
        String booleanValue = String.valueOf(Boolean.parseBoolean(properties.getProperty(key)));
        if (booleanValue.equals(properties.getProperty(key))) {
            return Boolean.parseBoolean(booleanValue);
        } else {
            if (Files.isRegularFile(FabricLoader.getInstance().getConfigDir().resolve("automodpack.properties"))) {
                LOGGER.error("Invalid value for " + key + " in automodpack.properties. Value must be true or false. Value restarted to " + def);
            }
            properties.setProperty(key, String.valueOf(def));
            return def;
        }
    }
}