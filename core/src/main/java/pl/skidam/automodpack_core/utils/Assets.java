package pl.skidam.automodpack_core.utils;

import java.io.InputStream;

/**
 * Read access to the mod's own bundled {@code assets/automodpack/} resources. Resolves from this class's loader because core always loads from the outer jar, which is where the assets live - once, not once per impl - so
 * impl classes must never resolve them through their own jar.
 */
public final class Assets {

	private Assets() {}

	/** Opens a bundled asset; a missing one is a broken jar, not an empty read, so this throws. */
	public static InputStream stream(String path) {
		InputStream stream = Assets.class.getResourceAsStream(path);
		if (stream == null) throw new IllegalStateException("Bundled asset is missing: " + path);
		return stream;
	}
}
