package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.util.*;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_core.loader.FileInspection;
import pl.skidam.automodpack_core.loader.GeneratedBundle;
import pl.skidam.automodpack_core.loader.ModFileCache;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.loader.NestedConflicts;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientOverlaySnapshot;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStateJournal;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.GeneratedCopyState;
import pl.skidam.automodpack_core.update.InstanceTree;
import pl.skidam.automodpack_core.update.StateHistory;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileCache;

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
 * superseded overlay files, checkpoints drifted-file resets into the state history, rewrites
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
 * state history and CAS helpers it drives under the mutation lock).
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

	record Input(SelectedModpackTarget selectedTarget, ConnectionJsons.ConnectionInfo connectionInfo,
			ClientConfigJsons.ClientConfigFieldsV3 currentConfig, boolean prepareObjects, Map<String, UpdatePlan.FileState> consentedLocalModFiles) {
		Input {
			Objects.requireNonNull(selectedTarget, "selectedTarget");
			Objects.requireNonNull(currentConfig, "currentConfig");
			consentedLocalModFiles = Map.copyOf(consentedLocalModFiles == null ? Map.of() : consentedLocalModFiles);
		}

		Input(SelectedModpackTarget selectedTarget, ConnectionJsons.ConnectionInfo connectionInfo,
				ClientConfigJsons.ClientConfigFieldsV3 currentConfig, boolean prepareObjects) {
			this(selectedTarget, connectionInfo, currentConfig, prepareObjects, Map.of());
		}

		/** The target's flat manifest, a validated component of the selected target rather than a separately carried copy. */
		ModpackJsons.ModpackContentFields target() {
			return selectedTarget.flatTarget();
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
			Map<String, InstanceTree.TrackedFile> priorGameDir, SelectionIntent expectedPriorIntent, ClientConfigJsons.ClientConfigFieldsV3 currentConfig,
			ClientConfigJsons.ClientConfigFieldsV3 plannedConfig, Map<UpdatePlan.FileKey, UpdatePlan.FileState> files,
			ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		RemovalPreparation {
			files = Map.copyOf(files);
			expectedClientConfig = new ClientConfigJsons.ClientConfigFieldsV3(Objects.requireNonNull(expectedClientConfig, "expectedClientConfig"));
		}
	}

	private record AvailablePreInstall(Map<String, InstanceTree.TrackedFile> priorGameDir, Set<String> objectHashes) {}

	/** Inspection phase: observes live, overlay and projection state and produces the plan; expects {@link #reconcileEditableState} to have run already. */
	PreparedPlan buildPlan(Input input, FileCache cache, ModFileCache modCache) throws IOException {
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
		List<NestedConflicts.StandardRoot> standardRoots = inspectStandardRoots(cache, modCache);
		List<UpdatePlan.ModInfo> standardMods = new ArrayList<>();
		for (NestedConflicts.StandardRoot root : standardRoots)
			standardMods.add(new UpdatePlan.ModInfo(root.logicalPath(), root.mod().hash(), Files.size(root.mod().path()), root.mod().version(), root.mod().IDs(), root.mod().deps()));
		List<UpdatePlan.NestedCopy> previousCopies = previousGeneratedState == null ? List.of() : previousGeneratedState.nestedCopies();
		List<UpdatePlanner.NestedCandidate> nestedCandidates = input.prepareObjects()
				? inspectNestedCopies(input.target(), cache, projection, targetMods, standardRoots, previousCopies, forceCopyServices)
				: readGeneratedCopyState(input.target(), input.selectedTarget().selection().intent()).nestedCopies().stream().map(UpdatePlanner.NestedCandidate::previous).toList();
		ClientConfigJsons.ClientConfigFieldsV3 plannedConfig = input.connectionInfo() == null || !input.connectionInfo().isComplete()
				? ModpackUtils.planCachedModpackSelection(input.target().modpackId, logicalConfig)
				: ModpackUtils.planModpackSelection(input.target().modpackId, input.connectionInfo(), logicalConfig);

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, input.target(), files, forceCopyServices, targetMods, standardMods,
				previousCopies, nestedCandidates, selection, plannedConfig, input.consentedLocalModFiles()));
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
			AvailablePreInstall availablePreInstall = readAvailablePreInstall(installed.modpackId, cache);
			ClientProjectionView.Snapshot projection = projectionView.snapshot(cache);
			// The state history is immutable, so this read is reconciliation-order safe, unlike the old baseline file.
			reconcileEditableState(cache, projection, null);
			GeneratedCopyState generatedCopies = projection.generatedCopies();
			Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(installed, installed, null, projection,
					generatedCopies == null ? List.of() : generatedCopies.nestedCopies(), cache,
					Map.of(installed.modpackId, storage.overlaySnapshot(installed.modpackId, cache)));
			UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, availablePreInstall.priorGameDir(), files, availablePreInstall.objectHashes(), generatedCopies, plannedConfig));
			return new RemovalPreparation(plan, installed, availablePreInstall.priorGameDir(), expectedPriorIntent, currentConfig, plannedConfig, files, expectedClientConfig);
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
			List<Path> candidates = new ArrayList<>(projection.sourceCandidates(item.file));
			candidates.add(livePath(item));
			if (ClientObjectStore.acquireVerified(storeFile, operation.expectedObjectHash(), operation.expectedSize(), candidates, cache, ClientObjectStore.CorruptObjectPolicy.KEEP).missing())
				throw new IOException("Required object is absent from CAS and verified live locations: " + operation.expectedObjectHash());
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
		AvailablePreInstall preInstall = readAvailablePreInstall(previousId, cache);
		return new UpdatePlanner.SelectionContext(previousId, previousManifest, snapshot.files(), preInstall.priorGameDir(), preInstall.objectHashes());
	}

	private AvailablePreInstall readAvailablePreInstall(String modpackId, FileCache cache) throws IOException {
		Map<String, InstanceTree.TrackedFile> priorGameDir = StateHistory.priorGameDir(storage, modpackId);
		Set<String> availableObjects = new HashSet<>();
		for (InstanceTree.TrackedFile file : priorGameDir.values())
			if (FileIntegrity.matchesNamed(storage.objectFile(file.sha1()), file.size(), file.sha1(), cache)) availableObjects.add(file.sha1());
		return new AvailablePreInstall(priorGameDir, Set.copyOf(availableObjects));
	}

	/**
	 * Reconciles mutable editable client state against the active generation: deletes superseded overlay files,
	 * rewrites overlay tombstones, and silently resets drifted server-owned non-mod files. The instance timeline
	 * already snapshotted live if it was dirty; drift does not add another row. Callers run this immediately before
	 * planning so the plan observes post-reconciliation state.
	 *
	 * @param target
	 *            the modpack the plan will install, used to detect server-side replacements of editable files; {@code null} for removal planning
	 */
	void reconcileEditableState(FileCache cache, ModpackJsons.ModpackContentFields target) throws IOException {
		reconcileEditableState(cache, ClientProjectionView.open(storage).snapshot(cache), target);
	}

	/** Same reconciliation against a caller-held projection snapshot. */
	void reconcileEditableState(FileCache cache, ClientProjectionView.Snapshot projection, ModpackJsons.ModpackContentFields target) throws IOException {
		ModpackJsons.ModpackContentFields activeTarget = projection.target();
		if (activeTarget == null || activeTarget.list == null) return;
		Set<InstanceTree.Key> extra = new TreeSet<>(InstanceTree.Key.ORDER);
		for (var item : activeTarget.list) extra.add(new InstanceTree.Key(UpdatePlan.Root.GAME_DIR, "", LogicalPath.normalize(item.file)));
		StateHistory.snapshotIfDirty(storage, extra, ClientStateJournal.Kind.LIVE, activeTarget.modpackId, "before-reconcile");
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
			var targetItem = sameModpackTarget ? targetItems.get(LogicalPath.normalize(item.file)) : null;
			if (!Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS)) {
				if (projection.matchesPendingGameState(item.file, new UpdatePlan.FileState(null, -1, false))) continue;
				if (targetItem != null && !targetItem.sha1.equalsIgnoreCase(item.sha1)) {
					// The pack replaced the content while the file was removed: the removal intent is spent and the new version comes back.
					Files.deleteIfExists(overlay);
					deletedPaths.remove(LogicalPath.normalize(item.file));
					continue;
				}
				Files.deleteIfExists(overlay);
				deletedPaths.add(LogicalPath.normalize(item.file));
				continue;
			}
			long size = Files.size(live);
			String hash = FileIntegrity.observedHash(live, item.size, item.sha1, cache);
			UpdatePlan.FileState state = new UpdatePlan.FileState(hash, size, true);
			if (projection.matchesPendingGameState(item.file, state)) continue;
			if (item.sha1.equalsIgnoreCase(state.sha1()) && item.size == state.size()) {
				Files.deleteIfExists(overlay);
				deletedPaths.remove(LogicalPath.normalize(item.file));
				continue;
			}
			if (targetItem != null && !targetItem.sha1.equalsIgnoreCase(item.sha1)) {
				// The pack owner replaced the file: leave the player's bytes in place and let the plan install the new
				// server version once; the update entry's captures pin the replaced bytes for the state history.
				Files.deleteIfExists(overlay);
				deletedPaths.remove(LogicalPath.normalize(item.file));
				continue;
			}
			Path object = storage.objectFile(hash);
			if (!FileIntegrity.matchesNamed(object, size, hash, cache)) VerifiedFileTransfer.copyAtomicImmutable(live, object, size, hash, cache);
			VerifiedFileTransfer.copyAtomic(object, overlay, size, hash, cache);
			deletedPaths.remove(LogicalPath.normalize(item.file));
		}
		storage.writeOverlayState(activeTarget.modpackId, deletedPaths);
	}

	/** A drifted file the pack owns: the drifted bytes are acquired for the state history and the live file gets the pack version back, without a review. */
	private UpdatePlan.FileState resetDriftedFile(FileCache cache, ModpackJsons.ModpackContentFields.ModpackContentItem item, Path live, UpdatePlan.FileState drift) throws IOException {
		long packSize = item.size;
		Path object = storage.objectFile(item.sha1);
		if (!FileIntegrity.matchesNamed(object, packSize, item.sha1, cache)) {
			LOGGER.warn("Pack version is unavailable locally; keeping the drifted file in place: {}", item.file);
			return null;
		}
		Path driftObject = storage.objectFile(drift.sha1());
		if (!FileIntegrity.matchesNamed(driftObject, drift.size(), drift.sha1(), cache)) VerifiedFileTransfer.copyAtomicImmutable(live, driftObject, drift.size(), drift.sha1(), cache);
		VerifiedFileTransfer.copyAtomic(object, live, packSize, item.sha1, cache);
		return new UpdatePlan.FileState(item.sha1, packSize, true);
	}

	/** Silently resets client-side drift of an unchanged server-provided non-mod file so it never becomes an update prompt; the server changing the file stays a reviewable update. */
	private void resetDriftedServerFile(FileCache cache, ClientProjectionView.Snapshot projection, ModpackJsons.ModpackContentFields activeTarget,
			Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems, ModpackJsons.ModpackContentFields.ModpackContentItem item)
			throws IOException {
		if (targetItems.isEmpty()) return;
		String relative = LogicalPath.normalize(item.file);
		var targetItem = targetItems.get(relative);
		if (targetItem == null || !targetItem.sha1.equalsIgnoreCase(item.sha1) || ModpackPathPolicy.isActiveMod(relative, item.type)) return;
		Path live = livePath(item);
		if (!Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS)) return;
		long packSize = item.size;
		long size = Files.size(live);
		if (size == packSize && FileIntegrity.matchesNamed(live, packSize, item.sha1, cache)) return;
		UpdatePlan.FileState state = new UpdatePlan.FileState(cache.getOrComputeHash(live), size, true);
		if (projection.matchesPendingGameState(item.file, state)) return;
		if (state.sha1().equalsIgnoreCase(item.sha1) && packSize == state.size()) return;
		resetDriftedFile(cache, item, live, state);
	}

	private Path livePath(ModpackJsons.ModpackContentFields.ModpackContentItem item) {
		return storage.gameDirectory().resolve(LogicalPath.normalize(item.file));
	}

	private void populateStoreFromSources(ModpackJsons.ModpackContentFields target, FileCache cache,
			Function<ModpackJsons.ModpackContentFields.ModpackContentItem, List<Path>> sourceResolver) throws IOException {
		if (target.list == null) return;
		for (var item : target.list)
			ClientObjectStore.acquireVerified(storage.objectFile(item.sha1), item.sha1, item.size, sourceResolver.apply(item), cache, ClientObjectStore.CorruptObjectPolicy.KEEP);
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
		Map<String, UpdatePlan.FileState> advertisedLive = advertisedLiveFiles(target, installed);
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
			if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) putFileState(files, UpdatePlan.Root.GAME_DIR, storage.gameDirectory(), path, cache, advertisedLive);
		}
		for (String gamePath : projection.gamePaths()) {
			Path path = storage.gamePath(gamePath);
			if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) putFileState(files, UpdatePlan.Root.GAME_DIR, storage.gameDirectory(), path, cache, advertisedLive);
		}
		if (Files.isDirectory(storage.modsDirectory(), LinkOption.NOFOLLOW_LINKS)) {
			try (Stream<Path> stream = Files.list(storage.modsDirectory())) {
				for (Path path : stream.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).toList())
					putFileState(files, UpdatePlan.Root.GAME_DIR, storage.gameDirectory(), path, cache, advertisedLive);
			}
		}
		OwnershipLedger ledger = OwnershipLedger.fromFields(target.ownershipLedger);
		for (String logicalPath : ledger.entries().keySet()) {
			Optional<UpdatePlan.FileKey> cleanupKey = UpdatePlanner.managedCleanupKey(logicalPath);
			if (cleanupKey.isEmpty()) continue;
			UpdatePlan.FileKey key = cleanupKey.get();
			if (key.root() != UpdatePlan.Root.GAME_DIR) continue;
			Path path = storage.gameDirectory().resolve(key.relativePath()).normalize();
			if (path.startsWith(storage.gameDirectory()) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) putFileState(files, key.root(), storage.gameDirectory(), path, cache, advertisedLive);
		}
		return files;
	}

	private static Map<String, UpdatePlan.FileState> advertisedLiveFiles(ModpackJsons.ModpackContentFields target, ModpackJsons.ModpackContentFields installed) {
		Map<String, UpdatePlan.FileState> advertised = new HashMap<>();
		if (installed != null && installed.list != null) for (var item : installed.list) putAdvertisedLive(advertised, item);
		if (target != null && target.list != null) for (var item : target.list) putAdvertisedLive(advertised, item);
		return advertised;
	}

	private static void putAdvertisedLive(Map<String, UpdatePlan.FileState> advertised, ModpackJsons.ModpackContentFields.ModpackContentItem item) {
		if (item == null || item.file == null || item.sha1 == null) return;
		advertised.put(LogicalPath.normalize(item.file), new UpdatePlan.FileState(item.sha1, item.size, true));
	}

	private GeneratedCopyState readGeneratedCopyState(ModpackJsons.ModpackContentFields manifest, SelectionIntent intent) throws IOException {
		String digest = UpdateTransaction.digest(intent);
		if (digest.isEmpty()) throw new IOException("Cannot read generated-copy state without a selected group intent");
		return GeneratedCopyState.read(storage, manifest.modpackId, manifest.contentToken, digest);
	}

	private void putFileState(Map<UpdatePlan.FileKey, UpdatePlan.FileState> files, UpdatePlan.Root root, Path rootPath, Path path,
			FileCache cache, Map<String, UpdatePlan.FileState> advertisedLive) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
		String relative = LogicalPath.normalize(rootPath.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString());
		long size = Files.size(path);
		UpdatePlan.FileState advertised = advertisedLive.get(relative);
		String hash = advertised != null && advertised.regularFile() ? FileIntegrity.observedHash(path, advertised.size(), advertised.sha1(), cache) : cache.getOrComputeHash(path);
		files.put(new UpdatePlan.FileKey(root, relative), new UpdatePlan.FileState(hash, size, true));
	}

	private Path resolvedObject(ModpackJsons.ModpackContentFields.ModpackContentItem item, ClientProjectionView.Snapshot projection, FileCache cache) {
		long size = item.size;
		Path object = storage.objectFile(item.sha1);
		if (FileIntegrity.matchesNamed(object, size, item.sha1, cache)) return object;
		for (Path candidate : projection.sourceCandidates(item.file)) {
			if (FileIntegrity.matchesObject(candidate, object, size, item.sha1, cache)) return candidate;
		}
		return null;
	}

	private List<UpdatePlan.ModInfo> inspectTargetMods(ModpackJsons.ModpackContentFields target, FileCache cache, ModFileCache modCache, ClientProjectionView.Snapshot projection) {
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		for (var item : target.list.stream().filter(value -> ModpackPathPolicy.isActiveMod(LogicalPath.normalize(value.file), value.type))
				.sorted(Comparator.comparing(value -> value.file)).toList()) {
			Path source = resolvedObject(item, projection, cache);
			if (source == null) continue;
			FileInspection.Mod mod = modCache.getModOrNull(source, item.sha1, cache);
			if (mod != null) mods.add(new UpdatePlan.ModInfo(LogicalPath.normalize(item.file), item.sha1, item.size, mod.version(), mod.IDs(), mod.deps()));
		}
		return mods;
	}

	private List<NestedConflicts.StandardRoot> inspectStandardRoots(FileCache cache, ModFileCache modCache) throws IOException {
		if (!Files.isDirectory(storage.modsDirectory())) return List.of();
		List<NestedConflicts.StandardRoot> roots = new ArrayList<>();
		try (Stream<Path> stream = Files.list(storage.modsDirectory())) {
			for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
				FileInspection.Mod mod = modCache.getModOrNull(path, cache);
				if (mod == null) continue;
				String relativePath = LogicalPath.normalize(storage.gameDirectory().relativize(path.toAbsolutePath().normalize()).toString());
				// The managed bundle is a root jar with a manifest, so the scan would otherwise see it as a player mod.
				if (ModpackPathPolicy.isGeneratedBundlePath(relativePath)) continue;
				roots.add(new NestedConflicts.StandardRoot(relativePath, mod));
			}
		}
		return roots;
	}

	/**
	 * Detects the jars the standard roots need from the pack and bundles them into the one reserved generated
	 * jar: selection stays per-jar (conflict winners, their dependency drags, dependency-driven providers), but
	 * the emission is a single deterministic bundle at {@link ModpackPathPolicy#GENERATED_BUNDLE_NAME}, hashed
	 * and acquired into the object store as one content object. Each candidate streams straight out of its pack
	 * root's jar by entry chain, and every source is read to the end as a zip first: the loader's boot scan reads
	 * every entry of a nested jar, so a jar that does not stream clean fails the plan here instead of crashing
	 * resolution there. The bundle never provisions its own contents' dependencies: when the previous state lists
	 * the reserved path it is passed to the detector as a previously generated copy.
	 */
	private List<UpdatePlanner.NestedCandidate> inspectNestedCopies(ModpackJsons.ModpackContentFields target, FileCache cache,
			ClientProjectionView.Snapshot projection, List<UpdatePlan.ModInfo> targetMods, List<NestedConflicts.StandardRoot> standardRoots,
			List<UpdatePlan.NestedCopy> previousCopies, Set<String> forceCopyPaths) throws IOException {
		if (!modpackLoader.discoversNestedConflicts()) return List.of();
		List<NestedConflicts.PackRoot> packRoots = new ArrayList<>();
		for (var item : target.list.stream().filter(value -> ModpackPathPolicy.isActiveMod(LogicalPath.normalize(value.file), value.type)).toList()) {
			Path source = resolvedObject(item, projection, cache);
			if (source == null) continue;
			FileInspection.Mod root = FileInspection.getMod(source, cache);
			if (root != null) packRoots.add(new NestedConflicts.PackRoot(LogicalPath.normalize(item.file), root));
		}

		Set<String> packRootIds = new HashSet<>();
		targetMods.forEach(mod -> packRootIds.addAll(mod.ids()));
		List<GeneratedBundle.Item> items = new ArrayList<>();
		Set<NestedConflicts.Collider> colliders = new LinkedHashSet<>();
		Set<String> previouslyCopiedPaths = previousCopies.stream().map(UpdatePlan.NestedCopy::relativePath).collect(Collectors.toSet());
		for (NestedConflicts.Candidate candidate : NestedConflicts.detect(packRoots, standardRoots, packRootIds, previouslyCopiedPaths, forceCopyPaths)) {
			JarUtils.validateStreamedJar(candidate.source().open());
			items.add(new GeneratedBundle.Item(candidate.entryName(), candidate.source()));
			colliders.addAll(candidate.colliders());
		}
		if (items.isEmpty()) return List.of();
		Path staging = Files.createTempFile(storage.stagingDirectory(), "bundle-", ".jar");
		String hash;
		long size;
		try {
			CountingDigestStream counting = new CountingDigestStream(Files.newOutputStream(staging));
			try (OutputStream out = counting) {
				GeneratedBundle.generate(items, out);
			}
			hash = HexFormat.of().formatHex(counting.digest());
			size = counting.size;
			Path storeFile = storage.objectFile(hash);
			if (!FileIntegrity.matchesNamed(storeFile, size, hash, cache)) VerifiedFileTransfer.copyAtomicImmutable(staging, storeFile, size, hash, cache);
		} finally {
			Files.deleteIfExists(staging);
		}
		String relativePath = ModpackPathPolicy.generatedBundlePath();
		Path liveBundle = storage.gameDirectory().resolve(relativePath);
		if (Files.exists(liveBundle, LinkOption.NOFOLLOW_LINKS) && !isGeneratedBundle(liveBundle, relativePath, hash, previousCopies, cache)) {
			LOGGER.warn("A foreign file occupies the reserved generated-bundle path {}; this plan installs no generated dependency copies", relativePath);
			return List.of();
		}
		return List.of(new UpdatePlanner.NestedCandidate(new UpdatePlan.NestedCopy(relativePath, hash, size), colliders));
	}

	/** Whether the file at the reserved bundle path is ours: the freshly generated bytes or a previous generation's bundle. */
	private static boolean isGeneratedBundle(Path liveBundle, String relativePath, String bundleHash, List<UpdatePlan.NestedCopy> previousCopies, FileCache cache) throws IOException {
		if (!Files.isRegularFile(liveBundle, LinkOption.NOFOLLOW_LINKS)) return false;
		String observed = cache.getOrComputeHash(liveBundle);
		if (observed.equalsIgnoreCase(bundleHash)) return true;
		for (UpdatePlan.NestedCopy previous : previousCopies)
			if (previous.relativePath().equalsIgnoreCase(relativePath) && previous.sha1().equalsIgnoreCase(observed)) return true;
		return false;
	}

	/** Hashes and counts the bundle bytes while they stream to the staging file, so its identity never needs a second pass. */
	private static final class CountingDigestStream extends DigestOutputStream {
		private long size;

		CountingDigestStream(OutputStream out) {
			super(out, HashUtils.newSha1Digest());
		}

		byte[] digest() {
			return getMessageDigest().digest();
		}

		@Override
		public void write(int b) throws IOException {
			super.write(b);
			size++;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			super.write(b, off, len);
			size += len;
		}
	}

	private Set<String> getForceCopyMods(ModpackJsons.ModpackContentFields modpackContentFields, FileCache cache, ModFileCache modCache, ClientProjectionView.Snapshot projection) {
		Set<String> forceCopyServices = modpackLoader.forceCopyServices();
		if (forceCopyServices.isEmpty()) return Set.of();
		Set<String> forceCopyMods = new HashSet<>();
		for (ModpackJsons.ModpackContentFields.ModpackContentItem item : modpackContentFields.list) {
			if (!ModpackPathPolicy.isActiveMod(LogicalPath.normalize(item.file), item.type)) continue;
			Path modPath = resolvedObject(item, projection, cache);
			if (modPath == null) continue;
			FileInspection.Mod mod = modCache.getModOrNull(modPath, item.sha1, cache);
			if (mod != null) {
				if (!Collections.disjoint(mod.services(), forceCopyServices)) forceCopyMods.add(item.file);
				continue;
			}
			// A service-only jar has no mod metadata, so the inspection above misses it; its services are the only identity to check.
			if (!FileInspection.getServices(modPath, forceCopyServices).isEmpty()) forceCopyMods.add(item.file);
		}
		return forceCopyMods;
	}
}
