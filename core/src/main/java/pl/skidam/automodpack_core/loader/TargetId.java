package pl.skidam.automodpack_core.loader;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Stonecutter spelling of a supported target, e.g. {@code 1.20.1-fabric} - the key the mounted
 * impl is selected by. Detected from the running loader well before Preload; an unresolvable id is
 * a broken launch and crashes instead of continuing on a guess.
 */
public final class TargetId {
	private static final Pattern MC_VERSION_SHAPE = Pattern.compile("\\d+(\\.\\d+){1,3}(-[A-Za-z0-9.]+)?");
	private static final Set<String> LOADERS = Set.of("fabric", "forge", "neoforge");

	/** Builds and validates the id for a loader ({@code fabric}/{@code forge}/{@code neoforge}) and its Minecraft version. */
	public static String id(String loader, String mcVersion) {
		if (loader == null || !LOADERS.contains(loader)) throw new IllegalStateException("Cannot determine the AutoModpack loader id, got: " + loader);
		if (mcVersion == null || !MC_VERSION_SHAPE.matcher(mcVersion).matches())
			throw new IllegalStateException("Cannot determine the AutoModpack target id: unusable Minecraft version " + mcVersion + " for loader " + loader);
		return mcVersion.toLowerCase(Locale.ROOT) + "-" + loader;
	}

	private TargetId() {}
}
