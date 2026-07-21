package pl.skidam.automodpack_core.utils.launchers;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.config.ConfigTools;

public class LauncherVersionSwapper {

	public static boolean requiresLoaderVersionSwap(String serverLoaderType, String serverLoaderVersion) {
		if (!clientConfig.syncLoaderVersion) return false;
		if (serverLoaderType == null || serverLoaderVersion == null || !serverLoaderType.equalsIgnoreCase(LOADER)) return false;
		return MultiMCMeta.requiresLoaderVersionUpdate(serverLoaderType, serverLoaderVersion) || PandoraMeta.requiresLoaderVersionUpdate(serverLoaderVersion);
	}

	public static boolean swapLoaderVersion(String serverLoaderType, String serverLoaderVersion) throws IOException {
		if (!clientConfig.syncLoaderVersion || serverLoaderType == null || serverLoaderVersion == null || !serverLoaderType.equalsIgnoreCase(LOADER)) return false;
		boolean multiMcApplicable = MultiMCMeta.updateLoaderVersion(serverLoaderType, serverLoaderVersion);
		boolean pandoraApplicable = PandoraMeta.updateLoaderVersion(serverLoaderVersion);
		return multiMcApplicable || pandoraApplicable;
	}

	static JsonObject readJson(Path path) {
		try {
			return readJsonStrict(path);
		} catch (IOException e) {
			LOGGER.error("Failed to read launcher metadata at: {}", path, e);
			return null;
		}
	}

	static JsonObject readJsonStrict(Path path) throws IOException {
		if (!Files.exists(path)) return null;
		if (!Files.isRegularFile(path)) throw new IOException("Launcher metadata is not a regular file: " + path);
		try {
			return ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
		} catch (RuntimeException e) {
			throw new IOException("Invalid launcher metadata JSON: " + path, e);
		}
	}

	static void writeJsonAtomic(Path path, JsonObject json) throws IOException {
		ConfigTools.writeAtomic(path, json);
	}
}
