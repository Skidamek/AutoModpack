package pl.skidam.automodpack_core.modpack.group;

import java.util.Locale;
import java.util.Set;

public final class ModpackPathPolicy {
	private static final Set<String> PLAYER_LOCAL_ROOTS = Set.of("automodpack", "logs", "player-local", "saves", "screenshots", "server-resource-packs");
	private static final Set<String> RESERVED_LIVE_ROOTS = Set.of("mods", "config", "shaderpacks", "resourcepacks");

	private ModpackPathPolicy() {}

	public static boolean isPlayerLocal(String logicalPath) {
		return PLAYER_LOCAL_ROOTS.contains(firstComponent(logicalPath).toLowerCase(Locale.ROOT));
	}

	public static String typeForPath(String logicalPath) {
		String normalized = LogicalPath.requireCanonical(logicalPath);
		if (normalized.startsWith("config/")) return "config";
		if (normalized.startsWith("shaderpacks/")) return "shader";
		if (normalized.startsWith("resourcepacks/")) return "resourcepack";
		if (normalized.equals("options.txt")) return "mc_options";
		return "other";
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
		if ("mod".equals(type)) return isModPath(normalized);
		String firstComponent = firstComponent(normalized);
		String lowerFirstComponent = firstComponent.toLowerCase(Locale.ROOT);
		if (RESERVED_LIVE_ROOTS.contains(lowerFirstComponent) && !firstComponent.equals(lowerFirstComponent)) return false;
		if ("mods".equals(lowerFirstComponent)) return false;
		if (normalized.equalsIgnoreCase("options.txt") && !"mc_options".equals(type)) return false;
		return type.equals(typeForPath(normalized));
	}

	public static boolean isModPath(String logicalPath) {
		return logicalPath.startsWith("mods/") && logicalPath.length() > "mods/".length();
	}

	private static String firstComponent(String logicalPath) {
		int separator = logicalPath.indexOf('/');
		return separator < 0 ? logicalPath : logicalPath.substring(0, separator);
	}
}
