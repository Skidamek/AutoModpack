package pl.skidam.automodpack_core.utils.launchers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class LauncherVersionSwapper {

    private static final Gson GSON = new GsonBuilder().create();

    // Returns true if any launcher metadata was updated
    public static boolean swapLoaderVersion(String serverLoaderType, String serverLoaderVersion) {
        if (!clientConfig.syncLoaderVersion) return false;
        if (serverLoaderType == null || serverLoaderVersion == null) return false;

        if (!serverLoaderType.equalsIgnoreCase(LOADER)) {
            return false;
        }

        boolean updated = false;

        if (MultiMCMeta.updateLoaderVersion(serverLoaderType, serverLoaderVersion)) updated = true;
        if (PandoraMeta.updateLoaderVersion(serverLoaderVersion)) updated = true;

        // TODO: Add more launchers here

        return updated;
    }

    /**
     * Shared utility to safely read, modify, and write a JSON file.
     * @param path The path to the JSON file.
     * @param modifier A predicate that modifies the JsonObject. It must return true if changes were made (triggering a save), false otherwise.
     */
    public static boolean modifyJson(Path path, Predicate<JsonObject> modifier) {
        if (!Files.exists(path)) return false;

        try {
            String content = Files.readString(path);
            JsonObject json = GSON.fromJson(content, JsonObject.class);

            if (json == null) return false;

            // modifier.test(json) executes the modification logic and returns true if we need to save
            if (modifier.test(json)) {
                Files.writeString(path, GSON.toJson(json));
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to update launcher metadata at: {}", path, e);
        }

        return false;
    }
}