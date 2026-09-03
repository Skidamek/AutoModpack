package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoadSelection;
import pl.skidam.automodpack_core.loader.PinnedMods;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;

/** Loads an installed modpack projection into the running game without contacting the server. */
final class ProjectionLoader {

	/** The installed projection view of the owning updater; reading it may observe the pending transaction. */
	@FunctionalInterface
	interface StoredTarget {
		ModpackJsons.ModpackContentFields get() throws IOException;
	}

	private final ClientStorage storage;
	private final StoredTarget storedTarget;

	ProjectionLoader(ClientStorage storage, StoredTarget storedTarget) {
		this.storage = storage;
		this.storedTarget = storedTarget;
	}

	// Load the already-installed modpack without contacting the server or
	// reconciling local files against it. Used when update-on-launch is disabled
	// so the user can freely add/remove mods (e.g. a binary search) without
	// AutoModpack restoring or deleting them.
	void loadModpack() throws Exception {

		if (!Files.exists(storage.activeDirectory())) return;
		try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory())) {
			loadModpackMods(cache, modCache);
		}
	}

	void loadSelectedActiveProjection() throws Exception {
		if (!Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
		if (state == null) return;
		if (!ModpackId.isValid(clientConfig.selectedModpackId)) {
			LOGGER.warn("Skipping active modpack load after preload because the configured selected modpack ID is invalid: {}", clientConfig.selectedModpackId);
			return;
		}
		if (!clientConfig.selectedModpackId.equals(state.modpackId)) {
			LOGGER.warn("Skipping active modpack load after preload because active state belongs to {}, but the selected modpack is {}", state.modpackId,
					clientConfig.selectedModpackId);
			return;
		}
		loadModpack();
	}

	// Load the modpack mods that aren't already present in the standard mods
	// directory, without requiring a restart.
	private void loadModpackMods(FileCache cache, ModFileCache modCache) throws Exception {
		if (!preload) {
			LOGGER.info("Modpack is already loaded");
			return;
		}

		Set<String> liveHashes = new HashSet<>();
		List<Set<String>> liveJarIds = new ArrayList<>();
		try (Stream<Path> standardModsStream = Files.list(storage.modsDirectory())) {
			for (Path path : standardModsStream.filter(JarUtils::isRegularJar).toList()) {
				String hash = cache.getHashOrNull(path);
				if (hash != null) liveHashes.add(hash);
				FileInspection.Mod inspected = modCache.getModOrNull(path, cache);
				if (inspected != null) liveJarIds.add(inspected.IDs());
			}
		} catch (IOException e) {
			LOGGER.error("Failed to list standard mods directory", e);
		}

		Set<String> activeModPaths = Optional.ofNullable(storedTarget.get()).map(target -> target.list.stream()
				.filter(item -> ModpackPathPolicy.isActiveMod(item.file, item.type)).map(item -> LogicalPath.normalize(item.file)).collect(Collectors.toSet())).orElseGet(Set::of);
		List<String> pinnedModIds = clientConfig == null || clientConfig.pinnedModIds == null ? List.of() : clientConfig.pinnedModIds;
		Path activeModsDirectory = storage.activePath(ModpackPathPolicy.MODS_ROOT).toAbsolutePath().normalize();
		List<ModpackLoadSelection.Jar> projectionJars = new ArrayList<>();
		if (Files.isDirectory(activeModsDirectory, LinkOption.NOFOLLOW_LINKS)) {
			try (Stream<Path> activeMods = Files.walk(activeModsDirectory)) {
				for (Path path : activeMods.filter(JarUtils::isRegularJar).toList()) {
					String relative = activeModLogicalPath(activeModsDirectory, path);
					if (relative == null || !activeModPaths.contains(relative)) continue;
					Path jar = storage.activePath(relative);
					String hash = cache.getHashOrNull(jar);
					FileInspection.Mod inspected = modCache.getModOrNull(jar, cache);
					projectionJars.add(new ModpackLoadSelection.Jar(jar, hash, inspected == null ? Set.of() : inspected.IDs()));
				}
			} catch (IOException e) {
				LOGGER.error("Failed to list modpack mods directory", e);
			}
		}

		List<Path> modpackMods = ModpackLoadSelection.select(projectionJars, liveHashes, liveJarIds, pinnedModIds);
		Set<String> protectedIds = PinnedMods.protectedIds(pinnedModIds, liveJarIds);
		for (ModpackLoadSelection.Jar jar : projectionJars) {
			if (modpackMods.contains(jar.path())) continue;
			if (PinnedMods.protects(protectedIds, jar.ids()))
				LOGGER.warn("Skipping load of projection mod {} because pinned client mod ids {} are present in the default mods folder", jar.path().getFileName(), jar.ids());
		}

		MODPACK_LOADER.loadModpack(new ModpackLoadRequest(activeModsDirectory, modpackMods));
	}

	private static String activeModLogicalPath(Path activeModsDirectory, Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		if (!normalized.startsWith(activeModsDirectory) || normalized.equals(activeModsDirectory)) return null;
		return ModpackPathPolicy.MODS_ROOT + "/" + LogicalPath.normalize(activeModsDirectory.relativize(normalized).toString());
	}
}
