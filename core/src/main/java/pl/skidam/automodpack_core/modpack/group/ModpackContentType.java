package pl.skidam.automodpack_core.modpack.group;

import java.util.Set;

import pl.skidam.automodpack_core.utils.JarUtils;

/** Local labels derived from a logical path. The pack does not persist these. */
public final class ModpackContentType {
	public static final String MOD = "mod";
	public static final String CONFIG = "config";
	public static final String SHADER = "shader";
	public static final String RESOURCEPACK = "resourcepack";
	public static final String MINECRAFT_OPTIONS = "mc_options";
	public static final String OTHER = "other";
	public static final Set<String> ALL = Set.of(MOD, CONFIG, SHADER, RESOURCEPACK, MINECRAFT_OPTIONS, OTHER);

	private ModpackContentType() {}

	/** Path-derived kind for inspector counts, change copy, and Modrinth project URLs. */
	public static String kindForPath(String path) {
		if (path == null) return OTHER;
		if (ModpackPathPolicy.isModPath(path)) return MOD;
		return ModpackPathPolicy.typeForPath(path);
	}

	/** Jars plus shader and resource-pack trees are submitted to Modrinth and CurseForge. */
	public static boolean needsPlatformLookup(String path) {
		if (JarUtils.hasJarExtension(path)) return true;
		String kind = kindForPath(path);
		return SHADER.equals(kind) || RESOURCEPACK.equals(kind);
	}

	/** Modrinth project-page segment for a looked-up file. */
	public static String platformProjectKind(String path) {
		if (JarUtils.hasJarExtension(path)) return MOD;
		String kind = kindForPath(path);
		return SHADER.equals(kind) || RESOURCEPACK.equals(kind) ? kind : MOD;
	}
}
