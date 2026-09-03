package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.FileSystemException;
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
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.ClientOverlaySnapshot;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.GeneratedCopyState;
import pl.skidam.automodpack_core.update.PreservationVault;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_core.utils.launchers.LauncherVersionSwapper;

/**
 * Builds client update plans in two phases.
 *
 * <p>
 * <strong>Inspection</strong> observes the live game directory, overlays and projection, and produces the plan via
 * {@link UpdatePlanner}: {@link #buildPlan} and {@link #prepareRemoval}. Inspection never edits live, overlay or
 * vault state; it only fills the content-addressed object store with verified content-identical copies and hashes
 * files into the shared metadata cache.
 * </p>
 *
 * <p>
 * <strong>Reconciliation</strong> is the explicit mutating counterpart, {@link #reconcileEditableState}: it deletes
 * superseded overlay files, vaults edited or drifted bytes as {@link PreservationVault} replace claims, rewrites
 * overlay tombstones, and silently resets drifted server-owned non-mod files. Callers run it immediately before
 * {@link #buildPlan} so the plan observes post-reconciliation state.
 * </p>
 *
 * <p>
 * {@link #prepareRemoval} still runs reconciliation internally at its historical point, because its baseline
 * availability check must observe pre-reconciliation object-store state while its file inspection must observe
 * post-reconciliation state; that ordering makes an external split a behavior change, so the side effect stays,
 * explicitly named and documented.
 * </p>
 *
 * <p>
 * The only other durable client mutation point is UpdateTransactionExecutor committing the reviewed plan (plus the
 * PreservationVault and CAS helpers it drives under the mutation lock).
 * </p>
 */
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
			ClientConfigJsons.ClientConfigFieldsV3 currentConfig, boolean prepareObjects, Map<String, UpdatePlan.FileState> consentedLocalModFiles) {
		Input {
			Objects.requireNonNull(selectedTarget, "selectedTarget");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(currentConfig, "currentConfig");
			consentedLocalModFiles = Map.copyOf(consentedLocalModFiles == null ? Map.of() : consentedLocalModFiles);
		}

		Input(SelectedModpackTarget selectedTarget, ModpackJsons.ModpackContentFields target, ConnectionJsons.ConnectionInfo connectionInfo,
				ClientConfigJsons.ClientConfigFieldsV3 currentConfig, boolean prepareObjects) {
			this(selectedTarget, target, connectionInfo, currentConfig, prepareObjects, Map.of());
		}
	}

	record PreparedPlan(UpdatePlan plan, Map<UpdatePlan.FileKey, UpdatePlan.FileState> originalFiles, String overlayDigest,
			ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		PreparedPlan {
			originalFiles = Map.copyOf(originalFiles);
			if (!HashUtils.isCanonicalSha1(overlayDigest)) throw new IllegalArgumentException("Prepared overlay digest is invalid");
			expectedClientConfig = new ClientConfigJsons.ClientConfigFieldsV3(Objects.requireNonNull(expectedClientConfig, "expectedClientConfig"));
		}
	}

	record RemovalPreparation(UpdatePlan plan, ModpackJsons.ModpackContentFields installed,
			ClientStorageJsons.ClientBaselineFields baseline, SelectionIntent expectedPriorIntent, ClientConfigJsons.ClientConfigFieldsV3 currentConfig,
			ClientConfigJsons.ClientConfigFieldsV3 plannedConfig, Map<UpdatePlan.FileKey, UpdatePlan.FileState> files,
			ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		RemovalPreparation {
			files = Map.copyOf(files);
			expectedClientConfig = new ClientConfigJsons.ClientConfigFieldsV3(Objects.requireNonNull(expectedClientConfig, "expectedClientConfig"));
		}
	}

	private record AvailableBaseline(ClientStorageJsons.ClientBaselineFields fields, Set<String> objectHashes) {}

	/** Inspection phase: observes live, overlay and projection state and produces the plan; expects {@link #reconcileEditableState} to have run already. */
	PreparedPlan buildPlan(Input input, FileCache cache, ModFileCache modCache) throws Exception {
		ClientProjectionView projectionView = ClientProjectionView.open(storage);
		ClientProjectionView.Snapshot projection = projectionView.snapshot(cache);
		ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		ClientConfigJsons.ClientConfigFieldsV3 logicalConfig = projectionView.logicalConfig(input.currentConfig(), expectedClientConfig);
		ModpackJsons.ModpackContentFields installed = projection.target();
		Map<String, ClientOverlaySnapshot> overlaySnapshots = new HashMap<>();
		ClientOverlaySnapshot targetOverlay = storage.overlaySnapshot(input.target().modpackId, cache);
		overlaySnapshots.put(input.target().modpackId, targetOverlay);
		UpdatePlanner.SelectionContext selection = selectionContext(projection, cache, overlaySnapshots);
		GeneratedCopyState previousGeneratedState = installed == null ? null : projection.generatedCopies();
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(input.target(), installed, selection, projection,
				previousGeneratedState == null ? List.of() : previousGeneratedState.nestedCopies(), cache, overlaySnapshots);
		if (input.prepareObjects()) populateStoreFromProjection(input.target(), projection, cache);
		Set<String> forceCopyServices = getForceCopyMods(input.target(), cache, modCache, projection).stream().map(LogicalPath::normalize).collect(Collectors.toSet());
		List<UpdatePlan.ModInfo> targetMods = inspectTargetMods(input.target(), cache, modCache, projection);
		List<UpdatePlan.ModInfo> standardMods = inspectStandardMods(cache, modCache);
		List<UpdatePlan.NestedCopy> nestedCopies = input.prepareObjects()
				? inspectNestedCopies(input.target(), cache, projection)
				: readGeneratedCopyState(input.target(), input.selectedTarget().selection().intent()).nestedCopies();
		ClientConfigJsons.ClientConfigFieldsV3 plannedConfig = input.connectionInfo() == null || !input.connectionInfo().isComplete()
				? ModpackUtils.planCachedModpackSelection(input.target().modpackId, logicalConfig)
				: ModpackUtils.planModpackSelection(input.target().modpackId, input.connectionInfo(), logicalConfig);
		Map<String, UpdatePlan.FileState> editableOverlays = files.entrySet().stream().filter(entry -> entry.getKey().root() == UpdatePlan.Root.OVERLAY)
				.collect(Collectors.toMap(entry -> entry.getKey().relativePath(), Map.Entry::getValue));

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, input.target(), files, editableOverlays, forceCopyServices, targetMods, standardMods,
				previousGeneratedState == null ? List.of() : previousGeneratedState.nestedCopies(), nestedCopies, selection, plannedConfig, input.consentedLocalModFiles()));
		if (!LauncherVersionSwapper.requiresLoaderVersionSwap(input.target().loader, input.target().loaderVersion, logicalConfig.syncLoaderVersion, loaderType))
			return new PreparedPlan(plan, files, targetOverlay.digest(), expectedClientConfig);
		return new PreparedPlan(plan.withRestartReason(UpdatePlan.RestartReason.CHANGED_LOADER_VERSION), files, targetOverlay.digest(), expectedClientConfig);
	}

	RemovalPreparation prepareRemoval() throws Exception {
		ClientProjectionView projectionView = ClientProjectionView.open(storage);
		ModpackJsons.ModpackContentFields installed = projectionView.target();
		ClientStorageJsons.ClientGenerationStateFields activeState = storage.readActiveState();
		if (activeState == null || installed == null) throw new IOException("Active modpack generation state is missing");
		ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		ClientConfigJsons.ClientConfigFieldsV3 currentConfig = projectionView.logicalConfig(expectedClientConfig, expectedClientConfig);
		ClientConfigJsons.ClientConfigFieldsV3 plannedConfig = currentConfig;
		if (installed.modpackId.equals(plannedConfig.selectedModpackId)) plannedConfig = plannedConfig.withSelectedModpackId("");
		SelectionIntent expectedPriorIntent = new ClientSelectionStore(storage.selectionFile()).get(installed.modpackId).orElse(null);

		try (var cache = FileCache.open(storage.fileCacheDirectory())) {
			AvailableBaseline availableBaseline = readAvailableBaseline(installed.modpackId, cache);
			ClientStorageJsons.ClientBaselineFields baseline = availableBaseline.fields();
			ClientProjectionView.Snapshot projection = projectionView.snapshot(cache);
			// Deliberate, documented side effect: the baseline above had to observe pre-reconciliation state, while the inspection below must observe post-reconciliation state.
			reconcileEditableState(cache, projection, null);
			GeneratedCopyState generatedCopies = projection.generatedCopies();
			Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(installed, installed, null, projection,
					generatedCopies == null ? List.of() : generatedCopies.nestedCopies(), cache,
					Map.of(installed.modpackId, storage.overlaySnapshot(installed.modpackId, cache)));
			UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, baseline, files, availableBaseline.objectHashes(), generatedCopies, plannedConfig));
			return new RemovalPreparation(plan, installed, baseline, expectedPriorIntent, currentConfig, plannedConfig, files, expectedClientConfig);
		}
	}

	void populateStoreFromLogicalProjection(ModpackJsons.ModpackContentFields target, FileCache cache) throws IOException {
		populateStoreFromProjection(target, ClientProjectionView.open(storage).snapshot(cache), cache);
	}

	private void populateStoreFromProjection(ModpackJsons.ModpackContentFields target, ClientProjectionView.Snapshot projection, FileCache cache) throws IOException {
		populateStoreFromSources(target, cache, item -> projection.sourceCandidates(item.file));
	}

	void populateStoreFromCachedLocations(ModpackJsons.ModpackContentFields target, FileCache cache) throws IOException {
		ClientProjectionView.Snapshot projection = ClientProjectionView.open(storage).snapshot(cache);
		populateStoreFromSources(target, cache, item -> {
			List<Path> candidates = new ArrayList<>(projection.sourceCandidates(item.file));
			candidates.add(livePath(item));
			return candidates;
		});
	}

	void preparePlanObjects(UpdatePlan plan, ModpackJsons.ModpackContentFields targetManifest) throws IOException {
		try (var cache = FileCache.open(storage.fileCacheDirectory())) {
			ClientProjectionView.Snapshot projection = ClientProjectionView.open(storage).snapshot(cache);
			preparePlanObjects(plan, targetManifest, projection, cache);
		}
	}

	private void preparePlanObjects(UpdatePlan plan, ModpackJsons.ModpackContentFields targetManifest, ClientProjectionView.Snapshot projection, FileCache cache) throws IOException {
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> itemsByHash = targetManifest.list.stream()
				.collect(Collectors.toMap(item -> item.sha1.toLowerCase(Locale.ROOT), item -> item, (first, second) -> first));
		for (UpdatePlan.Operation operation : plan.operations()) {
			if (operation.operation() != UpdatePlan.OperationType.INSTALL_OBJECT) continue;
			Path storeFile = storage.objectFile(operation.expectedObjectHash());
			if (FileIntegrity.matchesNamed(storeFile, operation.expectedSize(), operation.expectedObjectHash(), cache)) continue;
			if (operation.root() == UpdatePlan.Root.OVERLAY) {
				Path overlay = storage.overlayFile(targetManifest.modpackId, operation.relativePath());
				if (FileIntegrity.matches(overlay, operation.expectedSize(), operation.expectedObjectHash(), cache)) {
					VerifiedFileTransfer.copyAtomicImmutable(overlay, storeFile, operation.expectedSize(), operation.expectedObjectHash(), cache);
					continue;
				}
				throw new IOException("Required editable overlay object is unavailable: " + operation.expectedObjectHash());
			}
			var item = itemsByHash.get(operation.expectedObjectHash().toLowerCase(Locale.ROOT));
			if (item == null) throw new IOException("Planned CAS object is unavailable: " + operation.expectedObjectHash());
			List<Path> candidates = projection.sourceCandidates(item.file);
			Path source = candidates.stream().filter(candidate -> FileIntegrity.matches(candidate, operation.expectedSize(), operation.expectedObjectHash(), cache)).findFirst().orElse(null);
			if (source == null) source = livePath(item);
			if (!FileIntegrity.matches(source, operation.expectedSize(), operation.expectedObjectHash(), cache))
				throw new IOException("Required object is absent from CAS and verified live locations: " + operation.expectedObjectHash());
			VerifiedFileTransfer.copyAtomicImmutable(source, storeFile, operation.expectedSize(), operation.expectedObjectHash(), cache);
		}
	}

	private UpdatePlanner.SelectionContext selectionContext(ClientProjectionView.Snapshot projection, FileCache cache,
			Map<String, ClientOverlaySnapshot> overlaySnapshots) throws IOException {
		ModpackJsons.ModpackContentFields previousManifest = projection.target();
		if (previousManifest == null) return null;
		String previousId = previousManifest.modpackId;
		ClientOverlaySnapshot snapshot = overlaySnapshots.get(previousId);
		if (snapshot == null) {
			snapshot = storage.overlaySnapshot(previousId, cache);
			overlaySnapshots.put(previousId, snapshot);
		}
		AvailableBaseline baseline = readAvailableBaseline(previousId, cache);
		return new UpdatePlanner.SelectionContext(previousId, previousManifest, snapshot.files(), baseline.fields(), baseline.objectHashes());
	}

	private AvailableBaseline readAvailableBaseline(String modpackId, FileCache cache) throws IOException {
		ClientStorageJsons.ClientBaselineFields baseline = ConfigTools.read(storage.baselineFile(modpackId), ClientStorageJsons.ClientBaselineFields.class).orElseGet(() -> {
			ClientStorageJsons.ClientBaselineFields empty = new ClientStorageJsons.ClientBaselineFields();
			empty.modpackId = modpackId;
			return empty;
		});
		Set<String> availableObjects = new HashSet<>();
		if (baseline.entries != null) for (var entry : baseline.entries) {
			if (entry == null || entry.absent || entry.objectHash == null || entry.size < 0) continue;
			String hash = entry.objectHash.toLowerCase(Locale.ROOT);
			if (FileIntegrity.matchesNamed(storage.objectFile(hash), entry.size, hash, cache)) availableObjects.add(hash);
		}
		return new AvailableBaseline(baseline, Set.copyOf(availableObjects));
	}

	/**
	 * Reconciles mutable editable client state against the active generation: deletes superseded overlay files,
	 * vaults edited or drifted bytes as {@link PreservationVault} replace claims, rewrites overlay tombstones, and
	 * silently resets drifted server-owned non-mod files. This is the deliberate mutating counterpart of
	 * {@link #buildPlan}; callers run it immediately before planning so the plan observes post-reconciliation state.
	 *
	 * @param target
	 *            the modpack the plan will install, used to detect server-side replacements of editable files; {@code null} for removal planning
	 */
	void reconcileEditableState(FileCache cache, ModpackJsons.ModpackContentFields target) throws IOException {
		reconcileEditableState(cache, ClientProjectionView.open(storage).snapshot(cache), target);
	}

	/** Same reconciliation against a caller-held projection snapshot, for callers whose baseline reads must stay pre-reconciliation. */
	void reconcileEditableState(FileCache cache, ClientProjectionView.Snapshot projection, ModpackJsons.ModpackContentFields target) throws IOException {
		ModpackJsons.ModpackContentFields activeTarget = projection.target();
		if (activeTarget == null || activeTarget.list == null) return;
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems = new HashMap<>();
		if (target != null && target.list != null) target.list.forEach(item -> targetItems.put(LogicalPath.normalize(item.file), item));
		boolean sameModpackTarget = target != null && target.modpackId.equals(activeTarget.modpackId);
		Set<String> deletedPaths = new TreeSet<>(storage.readOverlayState(activeTarget.modpackId).deletedPaths);
		for (var item : activeTarget.list) {
			if (!item.editable) {
				resetDriftedServerFile(cache, projection, activeTarget, targetItems, item);
				continue;
			}
			Path live = livePath(item);
			Path overlay = storage.overlayFile(activeTarget.modpackId, item.file);
			if (!Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS)) {
				if (projection.matchesPendingGameState(item.file, new UpdatePlan.FileState(null, -1, false))) continue;
				Files.deleteIfExists(overlay);
				deletedPaths.add(LogicalPath.normalize(item.file));
				continue;
			}
			String hash = cache.getOrComputeHash(live);
			long size = Files.size(live);
			UpdatePlan.FileState state = new UpdatePlan.FileState(hash, size, true);
			if (projection.matchesPendingGameState(item.file, state)) continue;
			if (item.sha1.equalsIgnoreCase(state.sha1()) && Long.parseLong(item.size) == state.size()) {
				Files.deleteIfExists(overlay);
				deletedPaths.remove(LogicalPath.normalize(item.file));
				continue;
			}
			var targetItem = sameModpackTarget ? targetItems.get(LogicalPath.normalize(item.file)) : null;
			if (targetItem != null && !targetItem.sha1.equalsIgnoreCase(item.sha1)) {
				// The pack owner replaced the file: vault the player's edited bytes and let the plan install the new server version once; edits after that are preserved again.
				Files.deleteIfExists(overlay);
				deletedPaths.remove(LogicalPath.normalize(item.file));
				Path object = storage.objectFile(hash);
				if (!FileIntegrity.matchesNamed(object, size, hash, cache)) VerifiedFileTransfer.copyAtomicImmutable(live, object, size, hash, cache);
				PreservationVault.replaceClaim(storage, activeTarget.modpackId, activeTarget.contentToken, PreservationVault.Reason.EDITABLE_RESET, UpdatePlan.Root.GAME_DIR, item.file, hash, size);
				continue;
			}
			Path object = storage.objectFile(hash);
			if (!FileIntegrity.matchesNamed(object, size, hash, cache)) VerifiedFileTransfer.copyAtomicImmutable(live, object, size, hash, cache);
			VerifiedFileTransfer.copyAtomic(object, overlay, size, hash, cache);
			deletedPaths.remove(LogicalPath.normalize(item.file));
		}
		storage.writeOverlayState(activeTarget.modpackId, deletedPaths);
	}

	/** A drifted file the pack owns: the drifted bytes go to the vault and the live file gets the pack version back, without a review. */
	private UpdatePlan.FileState resetDriftedFile(FileCache cache, ModpackJsons.ModpackContentFields activeTarget, ModpackJsons.ModpackContentFields.ModpackContentItem item,
			Path live, UpdatePlan.FileState drift, PreservationVault.Reason reason) throws IOException {
		long packSize = Long.parseLong(item.size);
		Path object = storage.objectFile(item.sha1);
		if (!FileIntegrity.matchesNamed(object, packSize, item.sha1, cache)) {
			LOGGER.warn("Pack version is unavailable locally; keeping the drifted file in place: {}", item.file);
			return null;
		}
		PreservationVault.replaceClaim(storage, activeTarget.modpackId, activeTarget.contentToken, reason, UpdatePlan.Root.GAME_DIR, item.file, drift.sha1(), drift.size());
		VerifiedFileTransfer.copyAtomic(object, live, packSize, item.sha1, cache);
		return new UpdatePlan.FileState(cache.rehash(live), Files.size(live), true);
	}

	/** Silently resets client-side drift of an unchanged server-provided non-mod file so it never becomes an update prompt; the server changing the file stays a reviewable update. */
	private void resetDriftedServerFile(FileCache cache, ClientProjectionView.Snapshot projection, ModpackJsons.ModpackContentFields activeTarget,
			Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems, ModpackJsons.ModpackContentFields.ModpackContentItem item) throws IOException {
		if (targetItems.isEmpty()) return;
		String relative = LogicalPath.normalize(item.file);
		var targetItem = targetItems.get(relative);
		if (targetItem == null || !targetItem.sha1.equalsIgnoreCase(item.sha1) || ModpackPathPolicy.isActiveMod(relative, item.type)) return;
		Path live = livePath(item);
		if (!Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS)) return;
		UpdatePlan.FileState state = new UpdatePlan.FileState(cache.getOrComputeHash(live), Files.size(live), true);
		if (projection.matchesPendingGameState(item.file, state)) return;
		if (state.sha1().equalsIgnoreCase(item.sha1) && Long.parseLong(item.size) == state.size()) return;
		resetDriftedFile(cache, activeTarget, item, live, state, PreservationVault.Reason.LOCAL_DRIFT);
	}

	private Path livePath(ModpackJsons.ModpackContentFields.ModpackContentItem item) {
		return storage.gameDirectory().resolve(LogicalPath.normalize(item.file));
	}

	private void populateStoreFromSources(ModpackJsons.ModpackContentFields target, FileCache cache,
			Function<ModpackJsons.ModpackContentFields.ModpackContentItem, List<Path>> sourceResolver) throws IOException {
		if (target.list == null) return;
		for (var item : target.list) {
			Path object = storage.objectFile(item.sha1);
			long size = Long.parseLong(item.size);
			if (FileIntegrity.matchesNamed(object, size, item.sha1, cache)) continue;
			for (Path source : sourceResolver.apply(item)) if (populateStoreObject(source, object, size, item.sha1, cache)) break;
		}
	}

	private static boolean populateStoreObject(Path source, Path object, long size, String sha1, FileCache cache) throws IOException {
		if (!FileIntegrity.matches(source, size, sha1, cache)) return false;
		VerifiedFileTransfer.copyAtomicImmutable(source, object, size, sha1, cache);
		cache.overwriteCache(object, sha1);
		return true;
	}

	private Map<UpdatePlan.FileKey, UpdatePlan.FileState> inspectFiles(ModpackJsons.ModpackContentFields target, ModpackJsons.ModpackContentFields installed,
			UpdatePlanner.SelectionContext selection, ClientProjectionView.Snapshot projection, List<UpdatePlan.NestedCopy> previousGeneratedCopies, FileCache cache,
			Map<String, ClientOverlaySnapshot> overlaySnapshots) throws IOException {
		Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = new HashMap<>();
		for (Map.Entry<String, UpdatePlan.FileState> entry : projection.files().entrySet()) files.put(new UpdatePlan.FileKey(UpdatePlan.Root.PROJECTION, entry.getKey()), entry.getValue());
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
		for (String gamePath : projection.gamePaths()) {
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

	private GeneratedCopyState readGeneratedCopyState(ModpackJsons.ModpackContentFields manifest, SelectionIntent intent) throws IOException {
		String digest = UpdateTransaction.digest(intent);
		if (digest.isEmpty()) throw new IOException("Cannot read generated-copy state without a selected group intent");
		return GeneratedCopyState.read(storage, manifest.modpackId, manifest.contentToken, digest);
	}

	private void putFileState(Map<UpdatePlan.FileKey, UpdatePlan.FileState> files, UpdatePlan.Root root, Path rootPath, Path path,
			FileCache cache) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
		String relative = LogicalPath.normalize(rootPath.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString());
		String hash = cache.getOrComputeHash(path);
		files.put(new UpdatePlan.FileKey(root, relative), new UpdatePlan.FileState(hash, Files.size(path), true));
	}

	private Path resolvedObject(ModpackJsons.ModpackContentFields.ModpackContentItem item, ClientProjectionView.Snapshot projection, FileCache cache) {
		long size = Long.parseLong(item.size);
		Path source = storage.objectFile(item.sha1);
		if (FileIntegrity.matches(source, size, item.sha1, cache)) return source;
		for (Path candidate : projection.sourceCandidates(item.file)) {
			if (FileIntegrity.matches(candidate, size, item.sha1, cache)) return candidate;
		}
		return null;
	}

	private List<UpdatePlan.ModInfo> inspectTargetMods(ModpackJsons.ModpackContentFields target, FileCache cache, ModFileCache modCache, ClientProjectionView.Snapshot projection) {
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		for (var item : target.list.stream().filter(value -> ModpackPathPolicy.isActiveMod(LogicalPath.normalize(value.file), value.type))
				.sorted(Comparator.comparing(value -> value.file)).toList()) {
			Path source = resolvedObject(item, projection, cache);
			if (source == null) continue;
			FileInspection.Mod mod = modCache.getModOrNull(source, cache);
			if (mod != null) mods.add(new UpdatePlan.ModInfo(LogicalPath.normalize(item.file), item.sha1, Long.parseLong(item.size), mod.IDs(), mod.deps()));
		}
		return mods;
	}

	private List<UpdatePlan.ModInfo> inspectStandardMods(FileCache cache, ModFileCache modCache) throws IOException {
		if (!Files.isDirectory(storage.modsDirectory())) return List.of();
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		try (Stream<Path> stream = Files.list(storage.modsDirectory())) {
			for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
				FileInspection.Mod mod = modCache.getModOrNull(path, cache);
				if (mod != null) {
					String relativePath = LogicalPath.normalize(storage.gameDirectory().relativize(path.toAbsolutePath().normalize()).toString());
					mods.add(new UpdatePlan.ModInfo(relativePath, mod.hash(), Files.size(path), mod.IDs(), mod.deps()));
				}
			}
		}
		return mods;
	}

	private List<UpdatePlan.NestedCopy> inspectNestedCopies(ModpackJsons.ModpackContentFields target, FileCache cache, ClientProjectionView.Snapshot projection) throws IOException {
		if (!modpackLoader.discoversNestedConflicts()) return List.of();
		Path inspectionDirectory = Files.createTempDirectory(storage.incomingDirectory(), "inspection-");
		try {
			for (var item : target.list.stream().filter(value -> ModpackPathPolicy.isActiveMod(LogicalPath.normalize(value.file), value.type)).toList()) {
				Path source = resolvedObject(item, projection, cache);
				if (source == null) continue;
				String logicalPath = LogicalPath.normalize(item.file);
				Path inspectionPath = inspectionDirectory.resolve(logicalPath).normalize();
				if (!inspectionPath.startsWith(inspectionDirectory)) throw new IOException("Mod inspection path escaped its temporary directory: " + item.file);
				materializeInspectionCopy(source, inspectionPath, Long.parseLong(item.size), item.sha1, cache);
			}

			List<UpdatePlan.NestedCopy> copies = new ArrayList<>();
			Set<String> targetPaths = new HashSet<>();
			for (FileInspection.Mod mod : modpackLoader.getModpackNestedConflicts(inspectionDirectory, cache)) {
				if (mod.path() == null || mod.hash() == null || !Files.isRegularFile(mod.path())) continue;
				long size = Files.size(mod.path());
				Path storeFile = storage.objectFile(mod.hash());
				if (!FileIntegrity.matchesNamed(storeFile, size, mod.hash(), cache)) VerifiedFileTransfer.copyAtomicImmutable(mod.path(), storeFile, size, mod.hash(), cache);
				Path targetPath = storage.modsDirectory().resolve(mod.path().getFileName()).normalize();
				if (!targetPath.startsWith(storage.gameDirectory())) throw new IOException("Nested mod target escaped the game directory: " + targetPath);
				String relativePath = LogicalPath.normalize(storage.gameDirectory().relativize(targetPath).toString());
				if (!targetPaths.add(relativePath)) throw new IOException("Nested mod conflicts share a loader-facing target path: " + relativePath);
				copies.add(new UpdatePlan.NestedCopy(relativePath, mod.hash(), size, mod.IDs()));
			}
			return copies;
		} finally {
			FileTrees.delete(inspectionDirectory);
		}
	}

	private static void materializeInspectionCopy(Path source, Path inspectionPath, long size, String sha1, FileCache cache) throws IOException {
		Files.createDirectories(inspectionPath.getParent());
		try {
			Files.createLink(inspectionPath, source);
			return;
		} catch (UnsupportedOperationException | FileSystemException ignored) {
		}
		VerifiedFileTransfer.copyAtomic(source, inspectionPath, size, sha1, cache);
	}

	private Set<String> getForceCopyMods(ModpackJsons.ModpackContentFields modpackContentFields, FileCache cache, ModFileCache modCache, ClientProjectionView.Snapshot projection) {
		Set<String> forceCopyServices = modpackLoader.forceCopyServices();
		if (forceCopyServices.isEmpty()) return Set.of();
		Set<String> forceCopyMods = new HashSet<>();
		for (ModpackJsons.ModpackContentFields.ModpackContentItem item : modpackContentFields.list) {
			if (!ModpackPathPolicy.isActiveMod(LogicalPath.normalize(item.file), item.type)) continue;
			Path modPath = resolvedObject(item, projection, cache);
			if (modPath == null) continue;
			FileInspection.Mod mod = modCache.getModOrNull(modPath, cache);
			if (mod != null && !Collections.disjoint(mod.services(), forceCopyServices)) forceCopyMods.add(item.file);
		}
		return forceCopyMods;
	}
}
