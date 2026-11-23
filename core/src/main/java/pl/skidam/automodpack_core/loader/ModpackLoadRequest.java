package pl.skidam.automodpack_core.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The loader-facing view of the active modpack projection.
 *
 * <p>
 * The root is carried explicitly because inferring it from one selected file is incorrect for
 * nested paths and makes each loader adapter invent its own path semantics.
 * </p>
 */
public record ModpackLoadRequest(Path activeModsDirectory, List<Path> modpackMods) {
	public ModpackLoadRequest {
		Path normalizedRoot = loadedSpelling(activeModsDirectory, "active mods directory");
		activeModsDirectory = normalizedRoot;
		modpackMods = Objects.requireNonNull(modpackMods, "modpack mods").stream().map(path -> {
			Path normalized = loadedSpelling(path, "modpack mod");
			if (normalized.equals(normalizedRoot) || !normalized.startsWith(normalizedRoot))
				throw new IllegalArgumentException("Modpack mod is outside the active mods directory: " + path);
			return normalized;
		}).distinct().sorted().toList();
	}

	/** True for jars outside the active projection, and for projection jars that this request asked to load. */
	public boolean allowsProjectionJar(Path jar) {
		return !isProjectionJar(jar) || modpackMods.contains(loadedSpelling(jar, "jar"));
	}

	/** True for jars inside the active projection, whether or not this request asked to load them. */
	public boolean isProjectionJar(Path jar) {
		Path path = loadedSpelling(jar, "jar");
		return !path.equals(activeModsDirectory) && path.startsWith(activeModsDirectory);
	}

	/**
	 * The one spelling both sides of the projection question are compared in. A loader discovers jars through its
	 * own path normalization, which resolves links and junctions as soon as the file exists
	 * ({@code LoaderUtil.normalizePath} -> {@link Path#toRealPath()}); a request root that is only
	 * {@link Path#toAbsolutePath() absolute-normalized} then never prefixes the discovered paths, every projection
	 * jar reads as a live jar, and the whole selection - pin, live-path, hash, and editable exclusions alike -
	 * silently stops applying. A path that does not exist yet keeps the plain absolute spelling.
	 */
	private static Path loadedSpelling(Path path, String description) {
		Path absolute = Objects.requireNonNull(path, description).toAbsolutePath().normalize();
		try {
			return absolute.toRealPath();
		} catch (IOException unavailable) {
			return absolute;
		}
	}
}
