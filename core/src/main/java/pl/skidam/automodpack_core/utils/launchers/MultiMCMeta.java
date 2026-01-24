package pl.skidam.automodpack_core.utils.launchers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import pl.skidam.automodpack_core.Constants;

import java.nio.file.Path;
import java.util.Map;

// MultiMC and forks like Prism and forks of forks like Fjord etc.
public class MultiMCMeta {

    private static final long DELAY = 5000;
    private static final Path MMC_PACK_PATH = Path.of("../mmc-pack.json");
    private static final Map<String, String> LOADER_UID_MAP = Map.of(
            "fabric", "net.fabricmc.fabric-loader",
            "quilt", "org.quiltmc.quilt-loader",
            "forge", "net.minecraftforge",
            "neoforge", "net.neoforged"
    );

    public static boolean updateLoaderVersion(String loaderType, String newVersion) {
        String targetUid = LOADER_UID_MAP.get(loaderType.toLowerCase());
        if (targetUid == null) return false;

        return LauncherVersionSwapper.modifyJson(MMC_PACK_PATH, json -> {
            if (!json.has("formatVersion") || json.get("formatVersion").getAsInt() != 1) {
                return false;
            }

            JsonArray components = json.getAsJsonArray("components");
            if (components == null) return false;

            boolean changed = false;
            for (JsonElement element : components) {
                JsonObject component = element.getAsJsonObject();
                if (component.has("uid") && component.get("uid").getAsString().equals(targetUid)) {

                    String currentVersion = component.has("version") ? component.get("version").getAsString() : null;

                    if (currentVersion == null) continue;

                    if (!newVersion.equals(currentVersion)) {
                        component.addProperty("version", newVersion);

                        // What's this for?!
                        if (component.has("cachedVersion")) {
                            component.addProperty("cachedVersion", newVersion);
                        }

                        changed = true;
                    }
                }
            }

            if (changed) {
                var preloadDeltaTime = System.currentTimeMillis() - Constants.PRELOAD_TIME;
                var delayRequired = DELAY - preloadDeltaTime;
                if (delayRequired > 0) {
                    try { // Hack for prism, it reverts our changes if we write them too quickly after launching the game?!?
                        Constants.LOGGER.info("Simulating a {} sec delay to avoid MultiMC/Prism overwrite issue...", delayRequired / 1000);
                        Thread.sleep(delayRequired);
                    } catch (InterruptedException e) {
                        Constants.LOGGER.error("Interrupted while simulating delay", e);
                    }
                }
                json.add("components", components);
                Constants.LOGGER.info("MultiMC/Prism: Updated loader version to {}", newVersion);
            }

            return changed;
        });
    }
}