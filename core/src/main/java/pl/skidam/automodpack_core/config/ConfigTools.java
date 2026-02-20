package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.*;

import com.google.gson.*;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import pl.skidam.automodpack_core.utils.AddressHelpers;

public class ConfigTools {

    public static Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().registerTypeAdapter(InetSocketAddress.class, new InetSocketAddressTypeAdapter()).create();

    private static class InetSocketAddressTypeAdapter implements JsonSerializer<InetSocketAddress>, JsonDeserializer<InetSocketAddress> {

        @Override
        public JsonElement serialize(InetSocketAddress src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.getHostString() + ":" + src.getPort());
        }

        @Override
        public InetSocketAddress deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            String address = json.getAsString();
            return AddressHelpers.parse(address);
        }
    }

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
    public static <T> T softLoad(Path configFile, Class<T> configClass) {
        try {
            if (Files.isRegularFile(configFile)) {
                String json = Files.readString(configFile);
                return GSON.fromJson(json, configClass);
            }
        } catch (Exception ignored) {}
        return null;
    }

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

        try {
            // create new config
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
        if (clientConfigOverride != null) {
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
    public static Jsons.ModpackContent loadModpackContent(Path modpackContentFile) {
        try {
            if (Files.isRegularFile(modpackContentFile)) {
                String json = Files.readString(modpackContentFile);
                return GSON.fromJson(json, Jsons.ModpackContent.class);
            }
        } catch (Exception e) {
            LOGGER.error("Couldn't load modpack content! {}", modpackContentFile.toAbsolutePath().normalize(), e);
        }
        return null;
    }

    public static void saveModpackContent(Path modpackContentFile, Jsons.ModpackContent configObject) {
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

    public static Jsons.ClientSelectionManagerFields loadClientSelectionManager(Path selectionFile) {
        try {
            if (Files.isRegularFile(selectionFile)) {
                String json = Files.readString(selectionFile);
                Jsons.ClientSelectionManagerFields obj = GSON.fromJson(json, Jsons.ClientSelectionManagerFields.class);
                if (obj == null) {
                    return new Jsons.ClientSelectionManagerFields();
                }
                return obj;
            }
        } catch (Exception e) {
            LOGGER.debug("Couldn't load client selection manager file (this is normal on first startup): {}", e.getMessage());
        }
        return new Jsons.ClientSelectionManagerFields();
    }

    public static void saveClientSelectionManager(Path selectionFile, Jsons.ClientSelectionManagerFields configObject) {
        try {
            if (!Files.isDirectory(selectionFile.getParent())) {
                Files.createDirectories(selectionFile.getParent());
            }

            Files.writeString(selectionFile, GSON.toJson(configObject), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("Couldn't save client selection manager!");
            e.printStackTrace();
        }
    }
}
