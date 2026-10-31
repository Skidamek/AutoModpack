package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.loader.ModFileCache;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoadSelection;
import pl.skidam.automodpack_core.loader.PinnedMods;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.ProjectionAvailability;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** Loads an installed modpack projection into the running game without contacting the server. */
final class ProjectionLoader {

	/**
	 * The installed projection view of the owning updater; reading it may observe the pending transaction. It must be
	 * called per read and never captured: a commit between two reads retires the journal an observed view would
	 * otherwise keep answering from.
	 */
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
		ProjectionAvailability.Refusal refusal = ProjectionAvailability.stateStanding(storage.gameDirectory());
		if (!refusal.none()) {
			LOGGER.error("Not loading the modpack projection because {}", refusal.reason());
			return;
		}
		try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory())) {
			loadModpackMods(cache, modCache);
		}
	}

	void loadSelectedActiveProjection() throws Exception {
		if (!Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return;
		String mismatch = ClientStorage.activeSelectionMismatch(storage.selectedModpackId(), storage.readActiveState());
		if (mismatch != null) {
			LOGGER.warn("Skipping active modpack load after preload because {}", mismatch);
			return;
		}
		// The scan-time gate could not decide, or refused by its state and this boot did not relaunch, so the loaders
		// that discover mods by directory never received the projection; loading only through the loader API would hide
		// that half-loaded state, so this boot refuses the projection and runs without it.
		ProjectionAvailability.Refusal withheld = ProjectionAvailability.loaderApiWithholding(storage.gameDirectory());
		if (!withheld.none()) {
			if (withheld.cause() != null) LOGGER.error("{}; not loading it through the loader API either", withheld.reason(), withheld.cause());
			else LOGGER.error("{}; not loading it through the loader API either", withheld.reason());
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

		Set<String> editableActiveModPaths = new HashSet<>();
		ModpackJsons.ModpackContentFields target = storedTarget.get();
		if (target != null && target.list != null) for (var item : target.list) {
			if (item.editable && ModpackPathPolicy.isActiveMod(item.file)) editableActiveModPaths.add(LogicalPath.normalize(item.file));
		}
		List<String> pinnedModIds = clientConfig == null || clientConfig.pinnedModIds == null ? List.of() : clientConfig.pinnedModIds;
		Path activeModsDirectory = storage.activePath(ModpackPathPolicy.MODS_ROOT).toAbsolutePath().normalize();
		Set<String> liveModPaths = new HashSet<>();
		List<ModpackLoadSelection.Jar> projectionJars = new ArrayList<>();
		if (Files.isDirectory(activeModsDirectory, LinkOption.NOFOLLOW_LINKS)) {
			try (Stream<Path> activeMods = Files.walk(activeModsDirectory)) {
				for (Path path : activeMods.filter(JarUtils::isRegularJar).toList()) {
					String relative = activeModLogicalPath(activeModsDirectory, path);
					if (relative == null) continue;
					if (Files.isRegularFile(storage.gamePath(relative), LinkOption.NOFOLLOW_LINKS)) liveModPaths.add(relative);
					Path jar = storage.activePath(relative);
					String hash = cache.getHashOrNull(jar);
					FileInspection.Mod inspected = modCache.getModOrNull(jar, cache);
					projectionJars.add(new ModpackLoadSelection.Jar(jar, relative, hash, editableActiveModPaths.contains(relative), inspected == null ? Set.of() : inspected.IDs()));
				}
			} catch (IOException e) {
				LOGGER.error("Failed to list modpack mods directory", e);
			}
		}

		List<Path> modpackMods = ModpackLoadSelection.select(projectionJars, liveModPaths, liveHashes, liveJarIds, pinnedModIds);
		long editableMods = projectionJars.stream().filter(ModpackLoadSelection.Jar::editable).count();
		if (editableMods > 0) LOGGER.info("{} editable mod(s) of this modpack load from the standard mods folder", editableMods);
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
