package pl.skidam.automodpack_core.loader;

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
		Path normalizedRoot = Objects.requireNonNull(activeModsDirectory, "active mods directory").toAbsolutePath().normalize();
		activeModsDirectory = normalizedRoot;
		modpackMods = Objects.requireNonNull(modpackMods, "modpack mods").stream().map(path -> {
			Path normalized = Objects.requireNonNull(path, "modpack mod").toAbsolutePath().normalize();
			if (normalized.equals(normalizedRoot) || !normalized.startsWith(normalizedRoot))
				throw new IllegalArgumentException("Modpack mod is outside the active mods directory: " + path);
			return normalized;
		}).distinct().sorted().toList();
	}
}
