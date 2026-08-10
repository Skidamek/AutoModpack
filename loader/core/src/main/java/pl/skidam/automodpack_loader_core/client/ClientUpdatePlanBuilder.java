package pl.skidam.automodpack_loader_core.client;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientOverlaySnapshot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.GeneratedCopyState;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_core.utils.launchers.LauncherVersionSwapper;

final class ClientUpdatePlanBuilder {
	private final ClientStorage storage;
	private final ModpackLoaderService modpackLoader;
	private final String loaderType;

	ClientUpdatePlanBuilder(ClientStorage storage, ModpackLoaderService modpackLoader, String loaderType) {
		this.storage = Objects.requireNonNull(storage, "storage");
		this.modpackLoader = Objects.requireNonNull(modpackLoader, "modpackLoader");
		this.loaderType = Objects.requireNonNull(loaderType, "loaderType");
	}

	record Input(SelectedModpackTarget selectedTarget, ModpackJsons.ModpackContentFields target, ConnectionJsons.ConnectionInfo connectionInfo,
			ClientConfigJsons.ClientConfigFieldsV3 currentConfig, boolean prepareObjects) {
		Input {
			Objects.requireNonNull(selectedTarget, "selectedTarget");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(currentConfig, "currentConfig");
		}
	}

	record PreparedPlan(UpdatePlan plan, Map<UpdatePlan.FileKey, UpdatePlan.FileState> originalFiles, String overlayDigest) {
		PreparedPlan {
			originalFiles = Map.copyOf(originalFiles);
			if (overlayDigest == null || !overlayDigest.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Prepared overlay digest is invalid");
		}
	}

	record RemovalPreparation(UpdatePlan plan, ModpackJsons.CompleteModpackContentFields completeFields, ModpackJsons.ModpackContentFields installed,
			ClientStorageJsons.ClientBaselineFields baseline, SelectionIntent expectedPriorIntent, ClientConfigJsons.ClientConfigFieldsV3 currentConfig,
			ClientConfigJsons.ClientConfigFieldsV3 plannedConfig, Map<UpdatePlan.FileKey, UpdatePlan.FileState> files) {
		RemovalPreparation {
			files = Map.copyOf(files);
		}
	}

	PreparedPlan buildPlan(Input input, FileMetadataCache cache, ModFileCache modCache) throws Exception {
		captureActiveEditableOverlays(cache);
		ModpackJsons.ModpackContentFields installed = storedTarget();
		Map<String, ClientOverlaySnapshot> overlaySnapshots = new HashMap<>();
		ClientOverlaySnapshot targetOverlay = storage.overlaySnapshot(input.target().modpackId, cache);
		overlaySnapshots.put(input.target().modpackId, targetOverlay);
		UpdatePlanner.SelectionContext selection = selectionContext(input.currentConfig(), cache, overlaySnapshots);
		GeneratedCopyState previousGeneratedState = installed == null ? null : readGeneratedCopyState(installed, selection);
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(input.target(), installed, selection,
				previousGeneratedState == null ? List.of() : previousGeneratedState.nestedCopies(), cache, overlaySnapshots);
		if (input.prepareObjects()) populateStoreFromActive(input.target(), cache);
		Set<String> forceCopyServices = getForceCopyMods(input.target()).stream().map(UpdatePlanner::normalize).collect(Collectors.toSet());
		List<UpdatePlan.ModInfo> targetMods = inspectTargetMods(input.target(), cache, modCache);
		List<UpdatePlan.ModInfo> standardMods = inspectStandardMods(cache, modCache);
		List<UpdatePlan.NestedCopy> nestedCopies = input.prepareObjects()
				? inspectNestedCopies(input.target(), cache)
				: readGeneratedCopyState(input.target(), input.selectedTarget().selection().intent()).nestedCopies();
		ClientConfigJsons.ClientConfigFieldsV3 plannedConfig = input.connectionInfo() == null || !input.connectionInfo().isComplete()
				? ModpackUtils.planCachedModpackSelection(input.target().modpackId, input.currentConfig())
				: ModpackUtils.planModpackSelection(input.target().modpackId, input.connectionInfo(), input.currentConfig());
		Map<String, UpdatePlan.FileState> editableOverlays = files.entrySet().stream().filter(entry -> entry.getKey().root() == UpdatePlan.Root.OVERLAY)
				.collect(Collectors.toMap(entry -> entry.getKey().relativePath(), Map.Entry::getValue));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, input.target(), files, editableOverlays, forceCopyServices, targetMods, standardMods,
				previousGeneratedState == null ? List.of() : previousGeneratedState.nestedCopies(), nestedCopies, selection, plannedConfig));
		if (!LauncherVersionSwapper.requiresLoaderVersionSwap(input.target().loader, input.target().loaderVersion, input.currentConfig().syncLoaderVersion, loaderType))
			return new PreparedPlan(plan, files, targetOverlay.digest());
		Set<UpdatePlan.RestartReason> restartReasons = EnumSet.noneOf(UpdatePlan.RestartReason.class);
		restartReasons.addAll(plan.restartReasons());
		restartReasons.add(UpdatePlan.RestartReason.CHANGED_LOADER_VERSION);
		UpdatePlan withLoaderRestart = new UpdatePlan(plan.modpackId(), plan.generationTarget(), plan.operations(), plan.projectedFinalState(), plan.plannedClientConfig(),
				restartReasons, plan.preservations(), plan.baselineCaptures(), plan.conflicts(), plan.generatedCopies());
		return new PreparedPlan(withLoaderRestart, files, targetOverlay.digest());
	}

	RemovalPreparation prepareRemoval() throws Exception {
		ModpackJsons.ModpackContentFields installed = storedTarget();
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState == null || !installed.modpackId.equals(activeState.modpackId)) throw new IOException("Active modpack generation state is missing");
		ModpackJsons.CompleteModpackContentFields completeFields = new ClientGenerationStore(storage).read(activeState.generationId)
				.orElseThrow(() -> new IOException("Active client generation record is missing")).toFields();
		ClientStorageJsons.ClientBaselineFields baseline = ConfigTools.read(storage.baselineFile(installed.modpackId), ClientStorageJsons.ClientBaselineFields.class)
				.orElseGet(() -> {
					ClientStorageJsons.ClientBaselineFields empty = new ClientStorageJsons.ClientBaselineFields();
					empty.modpackId = installed.modpackId;
					return empty;
				});
		ClientConfigJsons.ClientConfigFieldsV3 currentConfig = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		ClientConfigJsons.ClientConfigFieldsV3 plannedConfig = new ClientConfigJsons.ClientConfigFieldsV3(currentConfig);
		if (installed.modpackId.equals(plannedConfig.selectedModpackId)) plannedConfig.selectedModpackId = "";
		SelectionIntent expectedPriorIntent = new ClientSelectionStore(storage.selectionFile()).get(installed.modpackId).orElse(null);
		GeneratedCopyState generatedCopies = expectedPriorIntent == null ? null : readGeneratedCopyState(installed, expectedPriorIntent);

		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(installed, installed, null,
					generatedCopies == null ? List.of() : generatedCopies.nestedCopies(), cache,
					Map.of(installed.modpackId, storage.overlaySnapshot(installed.modpackId, cache)));
			Set<String> availableBaselineObjects = new HashSet<>();
			if (baseline.entries != null) for (var entry : baseline.entries) {
				if (entry == null || entry.absent || entry.objectHash == null || entry.size < 0) continue;
				String hash = entry.objectHash.toLowerCase(Locale.ROOT);
				if (FileIntegrity.matches(storage.objectsDirectory().resolve(hash), entry.size, hash)) availableBaselineObjects.add(hash);
			}
			UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, availableBaselineObjects, generatedCopies, plannedConfig));
			return new RemovalPreparation(plan, completeFields, installed, baseline, expectedPriorIntent, currentConfig, plannedConfig, files);
		}
	}

	void populateStoreFromActive(ModpackJsons.ModpackContentFields target, FileMetadataCache cache) throws IOException {
		populateStoreFromSources(target, cache, item -> List.of(storage.activePath(item.file)));
	}

	void populateStoreFromCachedLocations(ModpackJsons.ModpackContentFields target, FileMetadataCache cache) throws IOException {
		populateStoreFromSources(target, cache,
				item -> List.of(storage.activePath(item.file), livePath(item)));
	}

	void ensurePlanObjects(UpdatePlan plan, ModpackJsons.ModpackContentFields targetManifest) throws IOException {
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> itemsByHash = targetManifest.list.stream()
				.collect(Collectors.toMap(item -> item.sha1.toLowerCase(Locale.ROOT), item -> item, (first, second) -> first));
		for (UpdatePlan.Operation operation : plan.operations()) {
			if (operation.operation() != UpdatePlan.OperationType.INSTALL_OBJECT) continue;
			Path storeFile = storage.objectsDirectory().resolve(operation.expectedObjectHash());
			if (FileIntegrity.matches(storeFile, operation.expectedSize(), operation.expectedObjectHash())) continue;
			if (operation.root() == UpdatePlan.Root.OVERLAY) {
				Path overlay = storage.overlayFile(targetManifest.modpackId, operation.relativePath());
				if (FileIntegrity.matches(overlay, operation.expectedSize(), operation.expectedObjectHash())) {
					VerifiedFileTransfer.copyAtomic(overlay, storeFile, operation.expectedSize(), operation.expectedObjectHash());
					continue;
				}
				throw new IOException("Required editable overlay object is unavailable: " + operation.expectedObjectHash());
			}
			var item = itemsByHash.get(operation.expectedObjectHash().toLowerCase(Locale.ROOT));
			if (item == null) throw new IOException("Planned CAS object is unavailable: " + operation.expectedObjectHash());
			Path source = storage.activePath(item.file);
			if (!FileIntegrity.matches(source, operation.expectedSize(), operation.expectedObjectHash())) source = livePath(item);
			if (!FileIntegrity.matches(source, operation.expectedSize(), operation.expectedObjectHash()))
				throw new IOException("Required object is absent from CAS and verified live locations: " + operation.expectedObjectHash());
			VerifiedFileTransfer.copyAtomic(source, storeFile, operation.expectedSize(), operation.expectedObjectHash());
		}
	}

	private ModpackJsons.ModpackContentFields storedTarget() throws IOException {
		return new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current()).map(SelectedModpackTarget::flatTarget).orElse(null);
	}

	private SelectedModpackTarget storedSelectedTarget() throws IOException {
		return new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current()).orElse(null);
	}

	private UpdatePlanner.SelectionContext selectionContext(ClientConfigJsons.ClientConfigFieldsV3 currentConfig, FileMetadataCache cache,
			Map<String, ClientOverlaySnapshot> overlaySnapshots) throws IOException {
		String previousId = currentConfig.selectedModpackId;
		if (previousId == null || previousId.isBlank() || !ModpackId.isValid(previousId)) return null;
		ModpackJsons.ModpackContentFields previousManifest = storedTarget();
		ClientOverlaySnapshot snapshot = overlaySnapshots.get(previousId);
		if (snapshot == null) {
			snapshot = storage.overlaySnapshot(previousId, cache);
			overlaySnapshots.put(previousId, snapshot);
		}
		return new UpdatePlanner.SelectionContext(previousId, previousManifest, snapshot.files());
	}

	private void captureActiveEditableOverlays(FileMetadataCache cache) throws IOException {
		SelectedModpackTarget activeTarget = storedSelectedTarget();
		if (activeTarget == null || activeTarget.flatTarget().list == null) return;
		storage.ensureRoots();
		Set<String> deletedPaths = new TreeSet<>(storage.readOverlayState(activeTarget.manifest().modpackId()).deletedPaths);
		for (var item : activeTarget.flatTarget().list) {
			if (!item.editable) continue;
			Path live = livePath(item);
			Path overlay = storage.overlayFile(activeTarget.manifest().modpackId(), item.file);
			if (!Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS)) {
				Files.deleteIfExists(overlay);
				deletedPaths.add(UpdatePlanner.normalize(item.file));
				continue;
			}
			String hash = cache.getHashOrNull(live);
			if (hash == null) {
				hash = HashUtils.getHash(live);
				if (hash != null) cache.overwriteCache(live, hash);
			}
			if (hash == null) throw new IOException("Failed to hash editable live file: " + live);
			long size = Files.size(live);
			if (item.sha1.equalsIgnoreCase(hash) && Long.parseLong(item.size) == size) {
				Files.deleteIfExists(overlay);
				deletedPaths.remove(UpdatePlanner.normalize(item.file));
				continue;
			}
			Path object = storage.objectsDirectory().resolve(hash);
			if (!FileIntegrity.matches(object, size, hash)) VerifiedFileTransfer.copyAtomic(live, object, size, hash);
			VerifiedFileTransfer.copyAtomic(object, overlay, size, hash);
			deletedPaths.remove(UpdatePlanner.normalize(item.file));
		}
		storage.writeOverlayState(activeTarget.manifest().modpackId(), deletedPaths);
	}

	private Path livePath(ModpackJsons.ModpackContentFields.ModpackContentItem item) {
		return storage.gameDirectory().resolve(UpdatePlanner.normalize(item.file));
	}

	private void populateStoreFromSources(ModpackJsons.ModpackContentFields target, FileMetadataCache cache,
			Function<ModpackJsons.ModpackContentFields.ModpackContentItem, List<Path>> sourceResolver) throws IOException {
		if (target.list == null) return;
		for (var item : target.list) {
			Path object = storage.objectsDirectory().resolve(item.sha1);
			long size = Long.parseLong(item.size);
			if (FileIntegrity.matches(object, size, item.sha1)) continue;
			for (Path source : sourceResolver.apply(item)) if (populateStoreObject(source, object, size, item.sha1, cache)) break;
		}
	}

	private static boolean populateStoreObject(Path source, Path object, long size, String sha1, FileMetadataCache cache) throws IOException {
		if (!FileIntegrity.matches(source, size, sha1)) return false;
		VerifiedFileTransfer.copyAtomic(source, object, size, sha1);
		cache.overwriteCache(object, sha1);
		return true;
	}

	private Map<UpdatePlan.FileKey, UpdatePlan.FileState> inspectFiles(ModpackJsons.ModpackContentFields target, ModpackJsons.ModpackContentFields installed,
			UpdatePlanner.SelectionContext selection, List<UpdatePlan.NestedCopy> previousGeneratedCopies, FileMetadataCache cache,
			Map<String, ClientOverlaySnapshot> overlaySnapshots) throws IOException {
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = new HashMap<>();
		if (Files.isDirectory(storage.activeDirectory())) {
			try (Stream<Path> stream = Files.walk(storage.activeDirectory())) {
				for (Path path : stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
					putFileState(files, UpdatePlan.Root.PROJECTION, storage.activeDirectory(), path, cache);
			}
		}
		ClientOverlaySnapshot targetOverlay = overlaySnapshots.get(target.modpackId);
		if (targetOverlay == null) {
			targetOverlay = storage.overlaySnapshot(target.modpackId, cache);
			overlaySnapshots.put(target.modpackId, targetOverlay);
		}
		for (var entry : targetOverlay.files().entrySet()) files.put(new UpdatePlan.FileKey(UpdatePlan.Root.OVERLAY, entry.getKey()), entry.getValue());
		Set<String> gamePaths = new HashSet<>();
		if (target.list != null) target.list.forEach(item -> gamePaths.add(item.file));
		if (installed != null && installed.list != null) installed.list.forEach(item -> gamePaths.add(item.file));
		if (selection != null && selection.previousManifest() != null && selection.previousManifest().list != null)
			selection.previousManifest().list.forEach(item -> gamePaths.add(item.file));
		previousGeneratedCopies.forEach(copy -> gamePaths.add(copy.relativePath()));
		if (installed != null && installed.ownershipLedger != null && installed.ownershipLedger.entries != null)
			installed.ownershipLedger.entries.forEach(entry -> {
				if (entry != null) gamePaths.add(entry.logicalPath);
			});
		for (String gamePath : gamePaths) {
			Path path = storage.gamePath(gamePath);
			if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) putFileState(files, UpdatePlan.Root.GAME_DIR, storage.gameDirectory(), path, cache);
		}
		if (Files.isDirectory(storage.modsDirectory(), LinkOption.NOFOLLOW_LINKS)) {
			try (Stream<Path> stream = Files.list(storage.modsDirectory())) {
				for (Path path : stream.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).toList())
					putFileState(files, UpdatePlan.Root.GAME_DIR, storage.gameDirectory(), path, cache);
			}
		}
		OwnershipLedger ledger = OwnershipLedger.fromFields(target.ownershipLedger);
		for (String logicalPath : ledger.entries().keySet()) {
			Optional<UpdatePlan.FileKey> cleanupKey = UpdatePlanner.managedCleanupKey(logicalPath);
			if (cleanupKey.isEmpty()) continue;
			UpdatePlan.FileKey key = cleanupKey.get();
			if (key.root() != UpdatePlan.Root.GAME_DIR) continue;
			Path path = storage.gameDirectory().resolve(key.relativePath()).normalize();
			if (path.startsWith(storage.gameDirectory()) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) putFileState(files, key.root(), storage.gameDirectory(), path, cache);
		}
		return files;
	}

	private GeneratedCopyState readGeneratedCopyState(ModpackJsons.ModpackContentFields manifest, UpdatePlanner.SelectionContext selection) throws IOException {
		SelectionIntent intent = selection == null ? null : new ClientSelectionStore(storage.selectionFile()).get(manifest.modpackId).orElse(null);
		return intent == null ? null : readGeneratedCopyState(manifest, intent);
	}

	private GeneratedCopyState readGeneratedCopyState(ModpackJsons.ModpackContentFields manifest, SelectionIntent intent) throws IOException {
		String digest = UpdateTransaction.digest(intent);
		if (digest.isEmpty()) throw new IOException("Cannot read generated-copy state without a selected group intent");
		return GeneratedCopyState.read(storage, manifest.modpackId, manifest.targetGenerationId, digest);
	}

	private void putFileState(Map<UpdatePlan.FileKey, UpdatePlan.FileState> files, UpdatePlan.Root root, Path rootPath, Path path,
			FileMetadataCache cache) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
		String relative = UpdatePlanner.normalize(rootPath.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString());
		String hash = cache.getHashOrNull(path);
		if (hash == null) {
			hash = HashUtils.getHash(path);
			if (hash == null) throw new IOException("Failed to hash live file: " + path);
			cache.overwriteCache(path, hash);
		}
		files.put(new UpdatePlan.FileKey(root, relative), new UpdatePlan.FileState(hash, Files.size(path), true));
	}

	private List<UpdatePlan.ModInfo> inspectTargetMods(ModpackJsons.ModpackContentFields target, FileMetadataCache cache, ModFileCache modCache) {
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		for (var item : target.list.stream().filter(value -> "mod".equals(value.type)).sorted(Comparator.comparing(value -> value.file)).toList()) {
			long size = Long.parseLong(item.size);
			Path source = storage.objectsDirectory().resolve(item.sha1);
			if (!FileIntegrity.matches(source, size, item.sha1)) source = storage.activePath(item.file);
			if (!FileIntegrity.matches(source, size, item.sha1)) continue;
			FileInspection.Mod mod = modCache.getModOrNull(source, cache);
			if (mod != null) mods.add(new UpdatePlan.ModInfo(UpdatePlanner.normalize(item.file), item.sha1, size, mod.IDs(), mod.deps()));
		}
		return mods;
	}

	private List<UpdatePlan.ModInfo> inspectStandardMods(FileMetadataCache cache, ModFileCache modCache) throws IOException {
		if (!Files.isDirectory(storage.modsDirectory())) return List.of();
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		try (Stream<Path> stream = Files.list(storage.modsDirectory())) {
			for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
				FileInspection.Mod mod = modCache.getModOrNull(path, cache);
				if (mod != null) {
					String relativePath = UpdatePlanner.normalize(storage.gameDirectory().relativize(path.toAbsolutePath().normalize()).toString());
					mods.add(new UpdatePlan.ModInfo(relativePath, mod.hash(), Files.size(path), mod.IDs(), mod.deps()));
				}
			}
		}
		return mods;
	}

	private List<UpdatePlan.NestedCopy> inspectNestedCopies(ModpackJsons.ModpackContentFields target, FileMetadataCache cache) throws IOException {
		Files.createDirectories(storage.incomingDirectory());
		Path inspectionDirectory = Files.createTempDirectory(storage.incomingDirectory(), "inspection-");
		try {
			Path inspectionMods = inspectionDirectory.resolve("mods");
			Files.createDirectories(inspectionMods);
			for (var item : target.list.stream().filter(value -> "mod".equals(value.type)).toList()) {
				Path source = storage.objectsDirectory().resolve(item.sha1);
				if (!FileIntegrity.matches(source, Long.parseLong(item.size), item.sha1)) source = storage.activePath(item.file);
				if (!FileIntegrity.matches(source, Long.parseLong(item.size), item.sha1)) continue;
				VerifiedFileTransfer.copyAtomic(source, inspectionMods.resolve(Path.of(UpdatePlanner.normalize(item.file)).getFileName()), Long.parseLong(item.size), item.sha1);
			}

			List<UpdatePlan.NestedCopy> copies = new ArrayList<>();
			for (FileInspection.Mod mod : modpackLoader.getModpackNestedConflicts(inspectionDirectory, cache)) {
				if (mod.path() == null || mod.hash() == null || !Files.isRegularFile(mod.path())) continue;
				long size = Files.size(mod.path());
				Path storeFile = storage.objectsDirectory().resolve(mod.hash());
				if (!FileIntegrity.matches(storeFile, size, mod.hash())) VerifiedFileTransfer.copyAtomic(mod.path(), storeFile, size, mod.hash());
				Path targetPath = storage.modsDirectory().resolve(mod.path().getFileName()).normalize();
				if (!targetPath.startsWith(storage.gameDirectory())) throw new IOException("Nested mod target escaped the game directory: " + targetPath);
				String relativePath = UpdatePlanner.normalize(storage.gameDirectory().relativize(targetPath).toString());
				copies.add(new UpdatePlan.NestedCopy(relativePath, mod.hash(), size, mod.IDs()));
			}
			return copies;
		} finally {
			try (Stream<Path> stream = Files.walk(inspectionDirectory)) {
				for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}

	private Set<String> getForceCopyMods(ModpackJsons.ModpackContentFields modpackContentFields) throws IOException {
		Set<String> forceCopyServices = modpackLoader.forceCopyServices();
		Set<String> forceCopyMods = new HashSet<>();
		if (forceCopyServices.isEmpty()) return forceCopyMods;

		for (ModpackJsons.ModpackContentFields.ModpackContentItem item : modpackContentFields.list) {
			if (!item.type.equals("mod")) continue;
			long size = Long.parseLong(item.size);
			Path modPath = storage.objectsDirectory().resolve(item.sha1);
			if (!FileIntegrity.matches(modPath, size, item.sha1)) modPath = storage.activePath(item.file);
			if (!FileIntegrity.matches(modPath, size, item.sha1)) continue;
			try (FileSystem fs = FileSystems.newFileSystem(modPath)) {
				if (!FileInspection.getServices(fs, forceCopyServices).isEmpty()) forceCopyMods.add(item.file);
			}
		}
		return forceCopyMods;
	}
}
