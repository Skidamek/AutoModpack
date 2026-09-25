package pl.skidam.automodpack_core.launchers;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.PRELOAD_TIME;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.launchers.LauncherVersionSwapper.Axis;
import pl.skidam.automodpack_core.storage.GameDirectory;

// MultiMC and forks like Prism and forks of forks like Fjord etc.
final class MultiMCMeta implements LauncherAdapter {

	private static final long DELAY = 5000;
	private static final String MC_UID = "net.minecraft";
	private static final Map<String, String> LOADER_UID_MAP = Map.of("fabric", "net.fabricmc.fabric-loader", "quilt", "org.quiltmc.quilt-loader", "forge",
			"net.minecraftforge", "neoforge", "net.neoforged");

	private final Path mmcPackPath;

	MultiMCMeta() {
		this(GameDirectory.current().getParent().resolve("mmc-pack.json"));
	}

	MultiMCMeta(Path mmcPackPath) {
		this.mmcPackPath = mmcPackPath;
	}

	@Override
	public boolean detected() {
		return Files.isRegularFile(mmcPackPath);
	}

	@Override
	public EnumSet<Axis> requiredAxes(String targetLoader, String targetLoaderVersion, String targetMcVersion) throws IOException {
		JsonObject json = LauncherVersionSwapper.readJsonStrict(mmcPackPath);
		if (!isSupported(json)) throw new IOException("Unsupported MultiMC/Prism launcher metadata at: " + mmcPackPath);
		String targetUid = componentUid(targetLoader);
		if (targetUid == null) throw new IOException("Unsupported modloader for MultiMC/Prism metadata: " + targetLoader);
		EnumSet<Axis> axes = EnumSet.noneOf(Axis.class);
		String mcVersion = componentVersion(json, MC_UID);
		if (mcVersion != null && !mcVersion.equals(targetMcVersion)) axes.add(Axis.GAME_VERSION);
		String currentUid = loaderUid(json);
		if (currentUid == null || !currentUid.equals(targetUid)) {
			axes.add(Axis.LOADER_TYPE);
		} else if (needsVersionUpdate(json, targetUid, targetLoaderVersion)) {
			axes.add(Axis.LOADER_VERSION);
		}
		return axes;
	}

	@Override
	public void apply(EnumSet<Axis> axes, String targetLoader, String targetLoaderVersion, String targetMcVersion) throws IOException {
		JsonObject json = LauncherVersionSwapper.readJsonStrict(mmcPackPath);
		if (!isSupported(json)) throw new IOException("Unsupported MultiMC/Prism launcher metadata at: " + mmcPackPath);
		String targetUid = componentUid(targetLoader);
		if (targetUid == null) throw new IOException("Unsupported modloader for MultiMC/Prism metadata: " + targetLoader);
		boolean changed = false;
		if (axes.contains(Axis.GAME_VERSION)) changed |= setComponentVersion(json, MC_UID, targetMcVersion);
		if (axes.contains(Axis.LOADER_TYPE)) changed |= replaceLoaderComponent(json, targetUid, targetLoaderVersion);
		if (axes.contains(Axis.LOADER_VERSION)) changed |= setComponentVersion(json, targetUid, targetLoaderVersion);
		if (changed) {
			waitForLauncherWriteWindow(mmcPackPath);
			LauncherVersionSwapper.writeJsonAtomic(mmcPackPath, json);
			LOGGER.info("MultiMC/Prism: switched launcher metadata to {} {} {}", targetLoader, targetLoaderVersion, targetMcVersion);
		}
		JsonObject persisted = LauncherVersionSwapper.readJsonStrict(mmcPackPath);
		for (Axis axis : axes) {
			if (!satisfied(persisted, axis, targetUid, targetLoaderVersion, targetMcVersion))
				throw new IOException("MultiMC/Prism launcher metadata did not converge for " + axis);
		}
	}

	private boolean satisfied(JsonObject json, Axis axis, String targetUid, String targetLoaderVersion, String targetMcVersion) {
		if (!isSupported(json)) return false;
		return switch (axis) {
			case GAME_VERSION -> targetMcVersion.equals(componentVersion(json, MC_UID));
			case LOADER_TYPE -> targetUid.equals(loaderUid(json));
			case LOADER_VERSION -> targetUid.equals(loaderUid(json)) && !needsVersionUpdate(json, targetUid, targetLoaderVersion);
		};
	}

	private static String componentUid(String loaderType) {
		return loaderType == null ? null : LOADER_UID_MAP.get(loaderType.toLowerCase(Locale.ROOT));
	}

	/** The uid of the loader component this instance currently runs, or null when the instance has none of the known loaders. */
	private static String loaderUid(JsonObject json) {
		for (JsonElement element : json.getAsJsonArray("components")) {
			JsonObject component = element.getAsJsonObject();
			if (!component.has("uid")) continue;
			String uid = component.get("uid").getAsString();
			if (LOADER_UID_MAP.containsValue(uid)) return uid;
		}
		return null;
	}

	private static String componentVersion(JsonObject json, String uid) {
		for (JsonElement element : json.getAsJsonArray("components")) {
			JsonObject component = element.getAsJsonObject();
			if (component.has("uid") && uid.equals(component.get("uid").getAsString()) && component.has("version"))
				return component.get("version").getAsString();
		}
		return null;
	}

	private static boolean setComponentVersion(JsonObject json, String uid, String version) {
		boolean changed = false;
		for (JsonElement element : json.getAsJsonArray("components")) {
			JsonObject component = element.getAsJsonObject();
			if (!component.has("uid") || !uid.equals(component.get("uid").getAsString()) || !component.has("version")) continue;
			if (!version.equals(component.get("version").getAsString())) {
				component.addProperty("version", version);
				changed = true;
			}
			if (component.has("cachedVersion") && !version.equals(component.get("cachedVersion").getAsString())) {
				component.addProperty("cachedVersion", version);
				changed = true;
			}
		}
		return changed;
	}

	/** Swaps whatever loader component the instance runs for the target one; a vanilla instance simply gains the loader component. */
	private static boolean replaceLoaderComponent(JsonObject json, String targetUid, String targetLoaderVersion) {
		if (targetUid.equals(loaderUid(json)) && !needsVersionUpdate(json, targetUid, targetLoaderVersion)) return false;
		JsonArray components = json.getAsJsonArray("components");
		boolean haveTarget = false;
		for (int i = components.size() - 1; i >= 0; i--) {
			JsonObject component = components.get(i).getAsJsonObject();
			if (!component.has("uid")) continue;
			String uid = component.get("uid").getAsString();
			if (!LOADER_UID_MAP.containsValue(uid)) continue;
			if (uid.equals(targetUid)) {
				haveTarget = true;
				component.addProperty("version", targetLoaderVersion);
				if (component.has("cachedVersion")) component.addProperty("cachedVersion", targetLoaderVersion);
				continue;
			}
			components.remove(i);
		}
		if (!haveTarget) {
			JsonObject loader = new JsonObject();
			loader.addProperty("uid", targetUid);
			loader.addProperty("version", targetLoaderVersion);
			components.add(loader);
		}
		return true;
	}

	private static boolean needsVersionUpdate(JsonObject json, String targetUid, String newVersion) {
		for (JsonElement element : json.getAsJsonArray("components")) {
			JsonObject component = element.getAsJsonObject();
			if (!component.has("uid") || !targetUid.equals(component.get("uid").getAsString()) || !component.has("version")) continue;
			if (!newVersion.equals(component.get("version").getAsString())) return true;
			if (component.has("cachedVersion") && !newVersion.equals(component.get("cachedVersion").getAsString())) return true;
		}
		return false;
	}

	private static boolean isSupported(JsonObject json) {
		if (json == null || !json.has("formatVersion") || !json.has("components") || !json.get("components").isJsonArray()) return false;
		try {
			return json.get("formatVersion").getAsInt() == 1;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Prism arms a single-shot 5 s save debounce on every launch (PackProfile::scheduleSave, fired by component data
	 * changes during the pre-spawn meta load), and its flush serializes stale in-memory state over our edit. The timer
	 * is armed before the game process spawns, so its flush lands before preload+5 s; past that deadline nothing
	 * re-dirties the profile during gameplay. Waits out the flush when it is still pending and writes immediately
	 * otherwise: the common fast path waits ~1-4 s instead of a fixed 5, and the helper (game long exited) does not
	 * wait at all. Prism re-reads mmc-pack.json at every launch (PackProfile::reload), so the edit is picked up next
	 * launch without restarting the launcher.
	 */
	private static void waitForLauncherWriteWindow(Path mmcPackPath) throws IOException {
		long deadline = PRELOAD_TIME + DELAY + 500;
		long snapshot = lastModifiedOrZero(mmcPackPath);
		while (System.currentTimeMillis() < deadline) {
			if (lastModifiedOrZero(mmcPackPath) != snapshot) {
				waitUntilMtimeStable(mmcPackPath, deadline);
				return;
			}
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted before MultiMC/Prism metadata could be persisted", e);
			}
		}
	}

	private static long lastModifiedOrZero(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	/** Insurance for forks that dirty twice during the online resolve: the flush is done once the mtime holds still, or once the write window closes. */
	private static void waitUntilMtimeStable(Path path, long deadline) throws IOException {
		long last = lastModifiedOrZero(path);
		while (System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted before MultiMC/Prism metadata could be persisted", e);
			}
			long current = lastModifiedOrZero(path);
			if (current == last) return;
			last = current;
		}
	}
}
