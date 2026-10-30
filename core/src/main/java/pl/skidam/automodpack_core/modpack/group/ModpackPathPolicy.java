package pl.skidam.automodpack_core.modpack.group;

import static pl.skidam.automodpack_core.Constants.MOD_ID;
import static pl.skidam.automodpack_core.storage.StoragePaths.BOOTSTRAP_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.MODPACK_CONTENT_FILE;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

import pl.skidam.automodpack_core.utils.JarUtils;

public final class ModpackPathPolicy {
	public static final String MODS_ROOT = "mods";
	public static final String CONFIG_ROOT = "config";
	public static final String SHADERPACKS_ROOT = "shaderpacks";
	public static final String RESOURCEPACKS_ROOT = "resourcepacks";
	public static final String MINECRAFT_OPTIONS_FILE = "options.txt";
	/** The reserved file name the client's generated dependency bundle lands at, always directly under {@link #MODS_ROOT}. */
	public static final String GENERATED_BUNDLE_NAME = "automodpack-generated.jar";
	private static final String MODS_PREFIX = MODS_ROOT + "/";

	/** Roots owned by the player or by AutoModpack itself; a server manifest cannot claim them. */
	private static final Set<String> RESERVED_ROOTS = Set.of("automodpack", "logs", "saves", "screenshots");
	/** Roots with client live-file semantics; descendants are valid only with their canonical lowercase spelling. */
	private static final Set<String> LIVE_ROOTS = Set.of(MODS_ROOT, CONFIG_ROOT, SHADERPACKS_ROOT, RESOURCEPACKS_ROOT);

	private ModpackPathPolicy() {}

	public static boolean isReservedPath(String logicalPath) {
		return RESERVED_ROOTS.contains(firstComponent(logicalPath).toLowerCase(Locale.ROOT));
	}

	/** Whether this mod id names AutoModpack itself; clients receive that jar through the self-update flow, never as pack content. */
	public static boolean isSelfModId(String id) {
		if (id == null || id.isBlank()) return false;
		String canonical = id.strip().toLowerCase(Locale.ROOT);
		return MOD_ID.equals(canonical) || (MOD_ID + "_bootstrap").equals(canonical) || (MOD_ID + "-bootstrap").equals(canonical) || (MOD_ID + "_mod").equals(canonical);
	}

	/** Whether any of the jar's ids names AutoModpack itself. */
	public static boolean isSelfMod(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) return false;
		for (String id : ids) if (isSelfModId(id)) return true;
		return false;
	}

	/** Whether {@code logicalPath} is the client's generated dependency bundle, the one file name a modpack manifest can never claim. */
	public static boolean isGeneratedBundlePath(String logicalPath) {
		return logicalPath.equalsIgnoreCase(generatedBundlePath());
	}

	/** The game-directory path the client's generated dependency bundle lives at. */
	public static String generatedBundlePath() {
		return LogicalPath.normalize(MODS_PREFIX + GENERATED_BUNDLE_NAME);
	}

	public static String typeForPath(String logicalPath) {
		String normalized = LogicalPath.requireCanonical(logicalPath);
		if (normalized.startsWith(CONFIG_ROOT + "/")) return ModpackContentType.CONFIG;
		if (normalized.startsWith(SHADERPACKS_ROOT + "/")) return ModpackContentType.SHADER;
		if (normalized.startsWith(RESOURCEPACKS_ROOT + "/")) return ModpackContentType.RESOURCEPACK;
		if (normalized.equals(MINECRAFT_OPTIONS_FILE)) return ModpackContentType.MINECRAFT_OPTIONS;
		return ModpackContentType.OTHER;
	}

	public static boolean isValidPublishedPath(String logicalPath) {
		final String normalized;
		try {
			normalized = LogicalPath.requireCanonical(logicalPath);
		} catch (RuntimeException e) {
			return false;
		}
		if (isReservedPath(normalized)) return false;
		if (isGeneratedBundlePath(normalized)) return false;
		if (normalized.equalsIgnoreCase(MODPACK_CONTENT_FILE.toString())) return false;
		if (normalized.equalsIgnoreCase(BOOTSTRAP_FILE.getFileName().toString())) return false;
		return !isInvalidLiveRoot(normalized);
	}

	public static boolean isActiveMod(String logicalPath) {
		try {
			String normalized = LogicalPath.requireCanonical(logicalPath);
			return isModPath(normalized) && JarUtils.hasJarExtension(normalized);
		} catch (RuntimeException e) {
			return false;
		}
	}

	public static boolean isModPath(String logicalPath) {
		return logicalPath.startsWith(MODS_PREFIX) && logicalPath.length() > MODS_PREFIX.length();
	}

	private static boolean isInvalidLiveRoot(String normalized) {
		String first = firstComponent(normalized);
		String lowerFirst = first.toLowerCase(Locale.ROOT);
		return LIVE_ROOTS.contains(lowerFirst) && (!first.equals(lowerFirst) || normalized.equals(first));
	}

	private static String firstComponent(String logicalPath) {
		int separator = logicalPath.indexOf('/');
		return separator < 0 ? logicalPath : logicalPath.substring(0, separator);
	}
}
