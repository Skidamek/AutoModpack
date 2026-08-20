
package pl.skidam.automodpack_core.config;

import com.google.gson.*;
import pl.skidam.automodpack_core.utils.AddressHelpers;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ConfigTools {

    public static Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .registerTypeAdapter(InetSocketAddress.class, new InetSocketAddressTypeAdapter())
            .create();

    private static class InetSocketAddressTypeAdapter implements JsonSerializer<InetSocketAddress>,JsonDeserializer<InetSocketAddress> {
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
        } catch (Exception ignored) { }
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

    public static void save(Path configFile, Object configObject) {
        try {
            if (!Files.isDirectory(configFile.getParent())) {
                Files.createDirectories(configFile.getParent());
            }

            JsonElement serialized = GSON.toJsonTree(configObject);
            if (serialized.isJsonObject() && Files.isRegularFile(configFile)
                    && configFile.toAbsolutePath().normalize().equals(serverConfigFile.toAbsolutePath().normalize())) {
                try {
                    JsonElement existing = new JsonParser().parse(Files.readString(configFile));
                    if (existing != null && existing.isJsonObject()) {
                        mergeUnknownFields(existing.getAsJsonObject(), serialized.getAsJsonObject());
                    }
                } catch (JsonParseException ignored) {
                    // Do not replace a malformed configuration while trying
                    // to preserve unknown fields. The normal loader reports it.
                }
            }

            Files.writeString(configFile, GSON.toJson(serialized), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("Couldn't save config! " + configObject.getClass());
            e.printStackTrace();
        }
    }

    private static void mergeUnknownFields(JsonObject existing, JsonObject serialized) {
        for (var entry : existing.entrySet()) {
            JsonElement current = serialized.get(entry.getKey());
            if (current == null) {
                serialized.add(entry.getKey(), entry.getValue().deepCopy());
            } else if (current.isJsonObject() && entry.getValue().isJsonObject()) {
                mergeUnknownFields(entry.getValue().getAsJsonObject(), current.getAsJsonObject());
            }
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
}
