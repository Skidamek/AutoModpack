package pl.skidam.automodpack_core.utils.launchers;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Path;

import com.google.gson.JsonObject;

public class PandoraMeta {

	private static final Path INFO_JSON_PATH = Path.of("../info_v1.json");

	public static boolean requiresLoaderVersionUpdate(String newVersion) {
		try {
			return needsUpdate(LauncherVersionSwapper.readJson(INFO_JSON_PATH), newVersion);
		} catch (RuntimeException e) {
			LOGGER.warn("Ignoring unsupported Pandora launcher metadata", e);
			return false;
		}
	}

	public static boolean updateLoaderVersion(String newVersion) throws IOException {
		JsonObject json = LauncherVersionSwapper.readJsonStrict(INFO_JSON_PATH);
		if (json == null || !json.has("preferred_loader_version")) return false;
		try {
			if (!newVersion.equals(json.get("preferred_loader_version").getAsString())) {
				json.addProperty("preferred_loader_version", newVersion);
				LauncherVersionSwapper.writeJsonAtomic(INFO_JSON_PATH, json);
				LOGGER.info("Pandora: Updated loader version to {}", newVersion);
			}
			if (needsUpdate(LauncherVersionSwapper.readJsonStrict(INFO_JSON_PATH), newVersion))
				throw new IOException("Pandora loader-version metadata did not converge");
			return true;
		} catch (RuntimeException e) {
			throw new IOException("Invalid Pandora launcher metadata", e);
		}
	}

	private static boolean needsUpdate(JsonObject json, String newVersion) {
		return json != null && json.has("preferred_loader_version") && !newVersion.equals(json.get("preferred_loader_version").getAsString());
	}
}
