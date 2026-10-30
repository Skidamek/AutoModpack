package pl.skidam.automodpack_core.modpack.group;

import java.util.Set;

import pl.skidam.automodpack_core.utils.JarUtils;

/** Canonical serialized file types used by modpack manifests and download routing. */
public final class ModpackContentType {
	public static final String MOD = "mod";
	public static final String CONFIG = "config";
	public static final String SHADER = "shader";
	public static final String RESOURCEPACK = "resourcepack";
	public static final String MINECRAFT_OPTIONS = "mc_options";
	public static final String OTHER = "other";
	public static final Set<String> ALL = Set.of(MOD, CONFIG, SHADER, RESOURCEPACK, MINECRAFT_OPTIONS, OTHER);

	private ModpackContentType() {}

	public static boolean isSourceFetchable(String type) {
		return MOD.equals(type) || SHADER.equals(type) || RESOURCEPACK.equals(type);
	}

	/** Files the confirm screen can call unverified, plus shaders and resource packs, are submitted to Modrinth and CurseForge so a miss is a real miss. */
	public static boolean needsPlatformLookup(String type, String path) {
		return isSourceFetchable(type) || JarUtils.hasJarExtension(path);
	}
}
