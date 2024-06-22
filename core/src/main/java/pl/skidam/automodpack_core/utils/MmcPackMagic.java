package pl.skidam.automodpack_core.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pl.skidam.automodpack_core.GlobalVariables;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class MmcPackMagic {
    public static final Path mmcPackFile = Paths.get("./../mmc-pack.json");
    public static final List<String> MINECRAFT_UID = Arrays.asList("net.minecraft", "net.fabricmc.intermediary");
    public static final List<String> FABRIC_LOADER_UID = List.of("net.fabricmc.fabric-loader");
    public static final List<String> QUILT_LOADER_UID = List.of("org.quiltmc.quilt-loader");
    public static final List<String> FORGE_LOADER_UID = List.of("net.minecraftforge");
    public static final List<String> NEOFORGE_LOADER_UID = List.of("net.neoforged");

    public static JsonObject getJson() throws IOException {
        return Json.fromFile(mmcPackFile);
    }

    public static void changeVersion(List<String> UID, String newVersion) throws IOException {
        if (newVersion == null || UID == null) {
            return;
        }

        if (!Files.exists(mmcPackFile)) {
            return;
        }

        JsonObject newJson = getJson().getAsJsonObject();
        if (newJson == null) {
            return;
        }

        JsonArray components = newJson.getAsJsonArray("components");

        for (JsonElement comp : components) {
            JsonObject component = comp.getAsJsonObject();
            if (UID.contains(component.get("uid").getAsString())) {
                String oldVersion = component.get("version").getAsString();
                component.remove("version");
                component.addProperty("version", newVersion);
                if (!oldVersion.equals(newVersion)) {
                    GlobalVariables.LOGGER.info("Changed version of " + component.get("uid").getAsString() + " to " + newVersion);
                }
            }
        };

        // change components of mmcPackJson to our components
        newJson.remove("components");
        newJson.add("components", components);

        Files.write(mmcPackFile, newJson.toString().getBytes());
    }
}
