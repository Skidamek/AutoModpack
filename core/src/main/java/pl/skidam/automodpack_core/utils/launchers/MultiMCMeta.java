package pl.skidam.automodpack_core.utils.launchers;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.PRELOAD_TIME;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

// MultiMC and forks like Prism and forks of forks like Fjord etc.
public class MultiMCMeta {

	private static final long DELAY = 5000;
	private static final Path MMC_PACK_PATH = Path.of("../mmc-pack.json");
	private static final Map<String, String> LOADER_UID_MAP = Map.of("fabric", "net.fabricmc.fabric-loader", "quilt", "org.quiltmc.quilt-loader", "forge",
			"net.minecraftforge", "neoforge", "net.neoforged");

	public static boolean requiresLoaderVersionUpdate(String loaderType, String newVersion) {
		String targetUid = LOADER_UID_MAP.get(loaderType.toLowerCase(Locale.ROOT));
		if (targetUid == null) return false;
		try {
			return needsUpdate(LauncherVersionSwapper.readJson(MMC_PACK_PATH), targetUid, newVersion);
		} catch (RuntimeException e) {
			LOGGER.warn("Ignoring unsupported MultiMC/Prism launcher metadata", e);
			return false;
		}
	}

	public static boolean updateLoaderVersion(String loaderType, String newVersion) throws IOException {
		String targetUid = LOADER_UID_MAP.get(loaderType.toLowerCase(Locale.ROOT));
		if (targetUid == null) return false;
		JsonObject json = LauncherVersionSwapper.readJsonStrict(MMC_PACK_PATH);
		try {
			if (!isSupported(json)) return false;
		} catch (RuntimeException e) {
			throw new IOException("Invalid MultiMC/Prism launcher metadata", e);
		}
		JsonArray components = json.getAsJsonArray("components");
		boolean applicable = false;
		boolean changed = false;
		try {
			for (JsonElement element : components) {
				JsonObject component = element.getAsJsonObject();
				if (!component.has("uid") || !targetUid.equals(component.get("uid").getAsString()) || !component.has("version")) continue;
				applicable = true;
				if (!newVersion.equals(component.get("version").getAsString())) {
					component.addProperty("version", newVersion);
					changed = true;
				}
				if (component.has("cachedVersion") && !newVersion.equals(component.get("cachedVersion").getAsString())) {
					component.addProperty("cachedVersion", newVersion);
					changed = true;
				}
			}
		} catch (RuntimeException e) {
			throw new IOException("Invalid MultiMC/Prism launcher metadata", e);
		}
		if (!applicable) return false;
		if (changed) {
			waitForLauncherWriteWindow();
			LauncherVersionSwapper.writeJsonAtomic(MMC_PACK_PATH, json);
			LOGGER.info("MultiMC/Prism: Updated loader version to {}", newVersion);
		}
		JsonObject persisted = LauncherVersionSwapper.readJsonStrict(MMC_PACK_PATH);
		try {
			if (needsUpdate(persisted, targetUid, newVersion)) throw new IOException("MultiMC/Prism loader-version metadata did not converge");
		} catch (RuntimeException e) {
			throw new IOException("Invalid persisted MultiMC/Prism launcher metadata", e);
		}
		return true;
	}

	private static boolean isSupported(JsonObject json) {
		return json != null && json.has("formatVersion") && json.get("formatVersion").getAsInt() == 1 && json.has("components")
				&& json.get("components").isJsonArray();
	}

	private static boolean needsUpdate(JsonObject json, String targetUid, String newVersion) {
		if (!isSupported(json)) return false;
		for (JsonElement element : json.getAsJsonArray("components")) {
			JsonObject component = element.getAsJsonObject();
			if (!component.has("uid") || !targetUid.equals(component.get("uid").getAsString()) || !component.has("version")) continue;
			if (!newVersion.equals(component.get("version").getAsString())) return true;
			if (component.has("cachedVersion") && !newVersion.equals(component.get("cachedVersion").getAsString())) return true;
		}
		return false;
	}

	private static void waitForLauncherWriteWindow() throws IOException {
		long delayRequired = DELAY - (System.currentTimeMillis() - PRELOAD_TIME);
		if (delayRequired <= 0) return;
		LOGGER.info("Simulating a {} sec delay to avoid MultiMC/Prism overwrite issue...", delayRequired / 1000);
		try {
			Thread.sleep(delayRequired);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted before MultiMC/Prism metadata could be persisted", e);
		}
	}
}
