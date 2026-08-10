package pl.skidam.automodpack_core.modpack.group;

import static pl.skidam.automodpack_core.storage.StoragePaths.MODPACK_CONTENT_FILE;

import java.util.Locale;
import java.util.Set;

public final class ModpackPathPolicy {
	public static final String MODS_ROOT = "mods";
	public static final String CONFIG_ROOT = "config";
	public static final String SHADERPACKS_ROOT = "shaderpacks";
	public static final String RESOURCEPACKS_ROOT = "resourcepacks";
	public static final String MINECRAFT_OPTIONS_FILE = "options.txt";
	private static final String MODS_PREFIX = MODS_ROOT + "/";
	private static final Set<String> PLAYER_LOCAL_ROOTS = Set.of("automodpack", "logs", "player-local", "saves", "screenshots", "server-resource-packs");
	private static final Set<String> RESERVED_LIVE_ROOTS = Set.of(MODS_ROOT, CONFIG_ROOT, SHADERPACKS_ROOT, RESOURCEPACKS_ROOT);

	private ModpackPathPolicy() {}

	public static boolean isPlayerLocal(String logicalPath) {
		return PLAYER_LOCAL_ROOTS.contains(firstComponent(logicalPath).toLowerCase(Locale.ROOT));
	}

	public static String typeForPath(String logicalPath) {
		String normalized = LogicalPath.requireCanonical(logicalPath);
		if (normalized.startsWith(CONFIG_ROOT + "/")) return ModpackContentType.CONFIG;
		if (normalized.startsWith(SHADERPACKS_ROOT + "/")) return ModpackContentType.SHADER;
		if (normalized.startsWith(RESOURCEPACKS_ROOT + "/")) return ModpackContentType.RESOURCEPACK;
		if (normalized.equals(MINECRAFT_OPTIONS_FILE)) return ModpackContentType.MINECRAFT_OPTIONS;
		return ModpackContentType.OTHER;
	}

	public static boolean isValidTypeAndPath(String logicalPath, String type) {
		if (type == null) return false;
		final String normalized;
		try {
			normalized = LogicalPath.requireCanonical(logicalPath);
		} catch (RuntimeException e) {
			return false;
		}
		if (isPlayerLocal(normalized)) return false;
		if (normalized.equalsIgnoreCase(MODPACK_CONTENT_FILE.toString())) return false;
		String firstComponent = firstComponent(normalized);
		String lowerFirstComponent = firstComponent.toLowerCase(Locale.ROOT);
		if (RESERVED_LIVE_ROOTS.contains(lowerFirstComponent) && (!firstComponent.equals(lowerFirstComponent) || normalized.equals(firstComponent))) return false;
		// A mod-shaped file keeps its mod metadata regardless of where the operator stores it.
		// Its logical path independently controls whether the client activates or only places it.
		if (ModpackContentType.MOD.equals(type)) return true;
		if (normalized.equalsIgnoreCase(MINECRAFT_OPTIONS_FILE) && !ModpackContentType.MINECRAFT_OPTIONS.equals(type)) return false;
		return type.equals(typeForPath(normalized));
	}

	public static boolean isActiveMod(String logicalPath, String type) {
		return ModpackContentType.MOD.equals(type) && isModPath(logicalPath);
	}

	public static boolean isModPath(String logicalPath) {
		return logicalPath.startsWith(MODS_PREFIX) && logicalPath.length() > MODS_PREFIX.length();
	}

	private static String firstComponent(String logicalPath) {
		int separator = logicalPath.indexOf('/');
		return separator < 0 ? logicalPath : logicalPath.substring(0, separator);
	}
}
