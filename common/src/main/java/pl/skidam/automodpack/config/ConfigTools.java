package pl.skidam.automodpack.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import static pl.skidam.automodpack.StaticVariables.*;

public class ConfigTools {

    public static Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();

    public static <T> T getConfigObject(Class<T> configClass) {
        T object = null;
        try {
            object = configClass.getConstructor().newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return object;
    }

    // Config stuff
    public static <T> T loadConfig(File configFile, Class<T> configClass) {
        try {
            if (!configFile.getParentFile().isDirectory()) {
                configFile.getParentFile().mkdirs();
            }

            if (configFile.isFile()) {
                String json = IOUtils.toString(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8));
                var obj = GSON.fromJson(json, configClass);
                saveConfig(configFile, obj);
                return obj;
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't load config! " + configClass);
            e.printStackTrace();
        }

        try { // create new config
            var obj = getConfigObject(configClass);
            saveConfig(configFile, obj);
            return obj;
        } catch (Exception e) {
            LOGGER.error("Invalid config class! " + configClass);
            e.printStackTrace();
            return null;
        }
    }

    public static void saveConfig(File configFile, Object configObject) {
        try {
            if (!configFile.getParentFile().isDirectory()) {
                configFile.getParentFile().mkdirs();
            }

            Files.writeString(configFile.toPath(), GSON.toJson(configObject), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("Couldn't save config! " + configObject.getClass());
            e.printStackTrace();
        }
    }


    // Modpack content stuff
    public static Jsons.ModpackContentFields loadModpackContent(File modpackContentFile) {
        try {
            if (modpackContentFile.isFile()) {
                String json = IOUtils.toString(new InputStreamReader(new FileInputStream(modpackContentFile), StandardCharsets.UTF_8));
                return GSON.fromJson(json, Jsons.ModpackContentFields.class);
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't load modpack content!");
            e.printStackTrace();
        }
        return null;
    }

    public static void saveModpackContent(File modpackContentFile, Jsons.ModpackContentFields configObject) {
        try {
            if (!modpackContentFile.getParentFile().isDirectory()) {
                modpackContentFile.getParentFile().mkdirs();
            }

            Files.writeString(modpackContentFile.toPath(), GSON.toJson(configObject), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("Couldn't save modpack content! " + configObject.getClass());
            e.printStackTrace();
        }
    }
}
