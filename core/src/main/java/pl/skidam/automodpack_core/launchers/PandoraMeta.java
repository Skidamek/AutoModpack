package pl.skidam.automodpack_core.launchers;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.launchers.LauncherVersionSwapper.Axis;
import pl.skidam.automodpack_core.storage.GameDirectory;

// Pandora launcher: the instance's info_v1.json is the full version record (crates/schema/src/instance.rs in Pandora's sources).
final class PandoraMeta implements LauncherAdapter {

	/** Canonical serde forms are lowercase (loader.rs rename_all); the capitalized names remain read aliases. */
	private static final Set<String> SWITCHABLE_LOADERS = Set.of("fabric", "forge", "neoforge");

	private final Path infoJsonPath;

	PandoraMeta() {
		this(GameDirectory.current().getParent().resolve("info_v1.json"));
	}

	PandoraMeta(Path infoJsonPath) {
		this.infoJsonPath = infoJsonPath;
	}

	@Override
	public boolean detected() {
		return Files.isRegularFile(infoJsonPath);
	}

	@Override
	public EnumSet<Axis> requiredAxes(String targetLoader, String targetLoaderVersion, String targetMcVersion) throws IOException {
		JsonObject json = LauncherVersionSwapper.readJsonStrict(infoJsonPath);
		if (json == null) throw new IOException("Unreadable Pandora launcher metadata at: " + infoJsonPath);
		EnumSet<Axis> axes = EnumSet.noneOf(Axis.class);
		String mcVersion = stringOrNull(json, "minecraft_version");
		if (mcVersion != null && !mcVersion.equals(targetMcVersion)) axes.add(Axis.GAME_VERSION);
		String currentLoader = currentLoader(json);
		if (currentLoader == null || !currentLoader.equals(targetLoader)) {
			axes.add(Axis.LOADER_TYPE);
		} else if (!pandoraLoaderVersion(targetLoader, targetLoaderVersion, targetMcVersion).equals(stringOrNull(json, "preferred_loader_version"))) {
			axes.add(Axis.LOADER_VERSION);
		}
		return axes;
	}

	@Override
	public void apply(EnumSet<Axis> axes, String targetLoader, String targetLoaderVersion, String targetMcVersion) throws IOException {
		JsonObject json = LauncherVersionSwapper.readJsonStrict(infoJsonPath);
		if (json == null) throw new IOException("Unreadable Pandora launcher metadata at: " + infoJsonPath);
		boolean changed = false;
		if (axes.contains(Axis.GAME_VERSION) && !targetMcVersion.equals(stringOrNull(json, "minecraft_version"))) {
			json.addProperty("minecraft_version", targetMcVersion);
			changed = true;
		}
		if (axes.contains(Axis.LOADER_TYPE) || axes.contains(Axis.LOADER_VERSION)) {
			if (!SWITCHABLE_LOADERS.contains(targetLoader)) throw new IOException("Unsupported modloader for Pandora metadata: " + targetLoader);
			if (!targetLoader.equals(stringOrNull(json, "loader"))) {
				json.addProperty("loader", targetLoader);
				changed = true;
			}
			String preferred = pandoraLoaderVersion(targetLoader, targetLoaderVersion, targetMcVersion);
			if (!preferred.equals(stringOrNull(json, "preferred_loader_version"))) {
				json.addProperty("preferred_loader_version", preferred);
				changed = true;
			}
		}
		if (changed) {
			LauncherVersionSwapper.writeJsonAtomic(infoJsonPath, json);
			LOGGER.info("Pandora: switched launcher metadata to {} {} {}", targetLoader, targetLoaderVersion, targetMcVersion);
		}
		JsonObject persisted = LauncherVersionSwapper.readJsonStrict(infoJsonPath);
		for (Axis axis : axes) {
			if (!satisfied(persisted, axis, targetLoader, targetLoaderVersion, targetMcVersion))
				throw new IOException("Pandora launcher metadata did not converge for " + axis);
		}
	}

	private boolean satisfied(JsonObject json, Axis axis, String targetLoader, String targetLoaderVersion, String targetMcVersion) {
		if (json == null) return false;
		return switch (axis) {
			case GAME_VERSION -> targetMcVersion.equals(stringOrNull(json, "minecraft_version"));
			case LOADER_TYPE -> targetLoader.equals(currentLoader(json));
			case LOADER_VERSION -> targetLoader.equals(currentLoader(json))
					&& pandoraLoaderVersion(targetLoader, targetLoaderVersion, targetMcVersion).equals(stringOrNull(json, "preferred_loader_version"));
		};
	}

	private static String currentLoader(JsonObject json) {
		String name = stringOrNull(json, "loader");
		return name == null ? null : switch (name.toLowerCase(Locale.ROOT)) {
			case "fabric", "forge", "neoforge" -> name.toLowerCase(Locale.ROOT);
			default -> null;
		};
	}

	/** Pandora keys Forge loader versions by their Minecraft-prefixed maven name; AutoModpack's own loader version is the bare one. */
	private static String pandoraLoaderVersion(String loader, String version, String mcVersion) {
		if (!"forge".equals(loader)) return version;
		if (version.startsWith(mcVersion + "-")) return version;
		return mcVersion + "-" + version;
	}

	private static String stringOrNull(JsonObject json, String member) {
		JsonElement element = json.get(member);
		return element == null || element.isJsonNull() ? null : element.getAsString();
	}
}
