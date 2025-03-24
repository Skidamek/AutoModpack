
package pl.skidam.automodpack_core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;


import static pl.skidam.automodpack_core.GlobalVariables.*;

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
    public static <T> T load(Path configFile, Class<T> configClass) {
        try {
            if (!Files.isDirectory(configFile.getParent())) {
                 Files.createDirectories(configFile.getParent());
            }

            if (Files.isRegularFile(configFile)) {
                String json = Files.readString(configFile);
                T obj = GSON.fromJson(json, configClass);
                if (obj == null) {
                    LOGGER.error("Parsed object is null. Possible JSON syntax error in file: " + configFile);
                    return null;
                }

                save(configFile, obj);
                return obj;
            }
        } catch (JsonSyntaxException e) {
            LOGGER.error("JSON syntax error while loading config! {} {}", configClass, e.getMessage());
            LOGGER.error("This error most often happens when you e.g. forget to put a comma between fields in JSON file. Check the file: " + configFile.toAbsolutePath().normalize());
            return null;
        } catch (Exception e) {
            LOGGER.error("Couldn't load config! " + configClass);
            e.printStackTrace();
        }

        try { // create new config
            T obj = getConfigObject(configClass);
            save(configFile, obj);
            return obj;
        } catch (Exception e) {
            LOGGER.error("Invalid config class! " + configClass);
            e.printStackTrace();
            return null;
        }
    }


    public static <T> T load(String json, Class<T> configClass) {
        try {
            if (json != null) {
	            return GSON.fromJson(json, configClass);
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't load config! " + configClass);
            e.printStackTrace();
        }

        return null;
    }

    public static void save(Path configFile, Object configObject) {
        if (clientConfigOverride != null && configObject instanceof Jsons.ClientConfigFields) {
            return;
        }

        try {
            if (!Files.isDirectory(configFile.getParent())) {
                Files.createDirectories(configFile.getParent());
            }

            Files.writeString(configFile, GSON.toJson(configObject), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("Couldn't save config! " + configObject.getClass());
            e.printStackTrace();
        }
    }


    // Modpack content stuff
    public static Jsons.ModpackContentFields loadModpackContent(Path modpackContentFile) {
        try {
            if (Files.isRegularFile(modpackContentFile)) {
                String json = Files.readString(modpackContentFile);
                return GSON.fromJson(json, Jsons.ModpackContentFields.class);
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't load modpack content! {}", modpackContentFile.toAbsolutePath().normalize(), e);
        }
        return null;
    }

    public static void saveModpackContent(Path modpackContentFile, Jsons.ModpackContentFields configObject) {
        try {
            if (!Files.isDirectory(modpackContentFile.getParent())) {
                Files.createDirectories(modpackContentFile.getParent());
            }

            Files.writeString(modpackContentFile, GSON.toJson(configObject), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("Couldn't save modpack content! " + configObject.getClass());
            e.printStackTrace();
        }
    }

    public static Set<String> loadFullServerPackExclude(Path serverConfigPath) {
        Set<String> excludedFiles = new HashSet<>();

        if (!Files.exists(serverConfigPath)) return excludedFiles;

        try (Reader reader = Files.newBufferedReader(serverConfigPath)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray excluded = json.getAsJsonArray("ServerPackExcluded");
            if (excluded != null) {
                for (JsonElement e : excluded) {
                    excludedFiles.add(e.getAsString());
                }
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.error("Error in automodpack-server.json with FullServerPack exclude", e);
        }

        return excludedFiles;
    }
}
