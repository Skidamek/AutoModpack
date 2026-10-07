package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.ModFileCache;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Active-mod paths whose jars ship any of the loader's force-copy services. The inspection cache answers from the
 * services that jar actually provides, so a service-only wrapper is visible the same way a described mod is.
 */
final class ForceCopyPaths {
	private ForceCopyPaths() {}

	static Set<String> ofActiveMods(Iterable<ModpackJsons.ModpackContentFields.ModpackContentItem> items, Set<String> services, Function<ModpackJsons.ModpackContentFields.ModpackContentItem, Path> resolve,
			ModFileCache mods, FileCache cache) {
		if (services == null || services.isEmpty()) return Set.of();
		Set<String> paths = new TreeSet<>();
		for (ModpackJsons.ModpackContentFields.ModpackContentItem item : items) {
			if (!ModpackPathPolicy.isActiveMod(LogicalPath.normalize(item.file), item.type)) continue;
			Path source = resolve.apply(item);
			if (source == null) continue;
			try {
				if (mods.inspect(source, item.sha1, cache).providesAny(services)) paths.add(LogicalPath.normalize(item.file));
			} catch (IOException e) {
				LOGGER.error("Failed to inspect {} for force-copy services", source, e);
			}
		}
		return Set.copyOf(paths);
	}
}
