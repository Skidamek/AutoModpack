package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
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
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientOverlaySnapshot;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStateJournal;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.ClientStorageMutation;
import pl.skidam.automodpack_core.update.GeneratedCopyState;
import pl.skidam.automodpack_core.update.PreInstallState;
import pl.skidam.automodpack_core.update.StateHistory;
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
			PreInstallState preInstall, SelectionIntent expectedPriorIntent, ClientConfigJsons.ClientConfigFieldsV3 currentConfig,
			ClientConfigJsons.ClientConfigFieldsV3 plannedConfig, Map<UpdatePlan.FileKey, UpdatePlan.FileState> files,
			ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		RemovalPreparation {
			files = Map.copyOf(files);
			expectedClientConfig = new ClientConfigJsons.ClientConfigFieldsV3(Objects.requireNonNull(expectedClientConfig, "expectedClientConfig"));
		}
	}

	private record AvailablePreInstall(PreInstallState preInstall, Set<String> objectHashes) {}

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
		List<UpdatePlan.ModInfo> standardMods = inspectStandardMods(cache, modCache);
		List<UpdatePlan.NestedCopy> nestedCopies = input.prepareObjects()
				? inspectNestedCopies(input.target(), cache, projection)
				: readGeneratedCopyState(input.target(), input.selectedTarget().selection().intent()).nestedCopies();
		ClientConfigJsons.ClientConfigFieldsV3 plannedConfig = input.connectionInfo() == null || !input.connectionInfo().isComplete()
				? ModpackUtils.planCachedModpackSelection(input.target().modpackId, logicalConfig)
				: ModpackUtils.planModpackSelection(input.target().modpackId, input.connectionInfo(), logicalConfig);

		UpdatePlan plan = UpdatePlanner.plan(new UpdatePlanner.Input(installed, input.target(), files, forceCopyServices, targetMods, standardMods,
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
			AvailablePreInstall availablePreInstall = readAvailablePreInstall(installed.modpackId, cache);
			ClientProjectionView.Snapshot projection = projectionView.snapshot(cache);
			// The state history is immutable, so this read is reconciliation-order safe, unlike the old baseline file.
			reconcileEditableState(cache, projection, null);
			GeneratedCopyState generatedCopies = projection.generatedCopies();
			Map<UpdatePlan.FileKey, UpdatePlan.FileState> files = inspectFiles(installed, installed, null, projection,
					generatedCopies == null ? List.of() : generatedCopies.nestedCopies(), cache,
					Map.of(installed.modpackId, storage.overlaySnapshot(installed.modpackId, cache)));
			UpdatePlan plan = UpdatePlanner.planRemoval(new UpdatePlanner.RemovalInput(installed, availablePreInstall.preInstall(), files, availablePreInstall.objectHashes(), generatedCopies, plannedConfig));
			return new RemovalPreparation(plan, installed, availablePreInstall.preInstall(), expectedPriorIntent, currentConfig, plannedConfig, files, expectedClientConfig);
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
		return new UpdatePlanner.SelectionContext(previousId, previousManifest, snapshot.files(), preInstall.preInstall(), preInstall.objectHashes());
	}

	private AvailablePreInstall readAvailablePreInstall(String modpackId, FileCache cache) throws IOException {
		PreInstallState preInstall = ClientStorageMutation.run(storage, () -> StateHistory.preInstallState(storage, modpackId));
		Set<String> availableObjects = new HashSet<>();
		for (PreInstallState.Entry entry : preInstall.entries())
			if (!entry.absent() && FileIntegrity.matchesNamed(storage.objectFile(entry.objectHash()), entry.size(), entry.objectHash(), cache)) availableObjects.add(entry.objectHash());
		return new AvailablePreInstall(preInstall, Set.copyOf(availableObjects));
	}

	/**
	 * Reconciles mutable editable client state against the active generation: deletes superseded overlay files,
	 * checkpoints drifted-file resets into the state history, rewrites overlay tombstones, and
	 * silently resets drifted server-owned non-mod files. This is the deliberate mutating counterpart of
	 * {@link #buildPlan}; callers run it immediately before planning so the plan observes post-reconciliation state.
	 *
	 * @param target
	 *            the modpack the plan will install, used to detect server-side replacements of editable files; {@code null} for removal planning
	 */
	void reconcileEditableState(FileCache cache, ModpackJsons.ModpackContentFields target) throws IOException {
		reconcileEditableState(cache, ClientProjectionView.open(storage).snapshot(cache), target);
	}

	/** Same reconciliation against a caller-held projection snapshot; drift resets it performed land as one DRIFT_RESET checkpoint. */
	void reconcileEditableState(FileCache cache, ClientProjectionView.Snapshot projection, ModpackJsons.ModpackContentFields target) throws IOException {
		ModpackJsons.ModpackContentFields activeTarget = projection.target();
		if (activeTarget == null || activeTarget.list == null) return;
		List<DriftReset> driftResets = new ArrayList<>();
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems = new HashMap<>();
		if (target != null && target.list != null) target.list.forEach(item -> targetItems.put(LogicalPath.normalize(item.file), item));
		boolean sameModpackTarget = target != null && target.modpackId.equals(activeTarget.modpackId);
		Set<String> deletedPaths = new TreeSet<>(storage.readOverlayState(activeTarget.modpackId).deletedPaths);
		for (var item : activeTarget.list) {
			if (!item.editable) {
				resetDriftedServerFile(cache, projection, activeTarget, targetItems, item, driftResets);
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
			long size = Files.size(live);
			String hash = FileIntegrity.observedHash(live, item.size, item.sha1, cache);
			UpdatePlan.FileState state = new UpdatePlan.FileState(hash, size, true);
			if (projection.matchesPendingGameState(item.file, state)) continue;
			if (item.sha1.equalsIgnoreCase(state.sha1()) && item.size == state.size()) {
				Files.deleteIfExists(overlay);
				deletedPaths.remove(LogicalPath.normalize(item.file));
				continue;
			}
			var targetItem = sameModpackTarget ? targetItems.get(LogicalPath.normalize(item.file)) : null;
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
		recordDriftResets(activeTarget.modpackId, driftResets);
	}

	/** A drifted file the pack owns: the drifted bytes are acquired for the state history and the live file gets the pack version back, without a review. */
	private UpdatePlan.FileState resetDriftedFile(FileCache cache, ModpackJsons.ModpackContentFields.ModpackContentItem item, Path live, UpdatePlan.FileState drift,
			List<DriftReset> driftResets) throws IOException {
		long packSize = item.size;
		Path object = storage.objectFile(item.sha1);
		if (!FileIntegrity.matchesNamed(object, packSize, item.sha1, cache)) {
			LOGGER.warn("Pack version is unavailable locally; keeping the drifted file in place: {}", item.file);
			return null;
		}
		Path driftObject = storage.objectFile(drift.sha1());
		if (!FileIntegrity.matchesNamed(driftObject, drift.size(), drift.sha1(), cache)) VerifiedFileTransfer.copyAtomicImmutable(live, driftObject, drift.size(), drift.sha1(), cache);
		VerifiedFileTransfer.copyAtomic(object, live, packSize, item.sha1, cache);
		driftResets.add(new DriftReset(LogicalPath.normalize(item.file), drift.sha1(), drift.size(), item.sha1, packSize));
		return new UpdatePlan.FileState(item.sha1, packSize, true);
	}

	/** One drift reset the reconciliation performed: the drifted bytes it captured and the pack version it restored. */
	private record DriftReset(String path, String driftHash, long driftSize, String packHash, long packSize) {}

	/**
	 * The state history checkpoint of a reconciliation's drift resets: the tracked manifest is unchanged - the pack
	 * version was always the manifest's truth - but the change list and the captured drift bytes record exactly what
	 * the reset touched, and the captures pin those bytes against collection.
	 */
	private void recordDriftResets(String modpackId, List<DriftReset> driftResets) throws IOException {
		if (driftResets.isEmpty()) return;
		ClientStorageMutation.run(storage, () -> {
			ClientStateJournal journal = ClientStateJournal.open(storage.stateHistoryJournalFile());
			if (journal.entries().isEmpty()) return null;
			ClientStateJournal.StateEntry head = journal.head();
			if (!head.modpackId().equals(modpackId)) throw new IOException("Drift resets belong to " + modpackId + " but the state history head is " + head.modpackId());
			List<ClientStateJournal.Change> changes = new ArrayList<>();
			List<ClientStateJournal.Capture> captures = new ArrayList<>();
			for (DriftReset reset : driftResets) {
				changes.add(ClientStateJournal.Change.install(UpdatePlan.Root.GAME_DIR, reset.path(), reset.driftHash(), reset.packHash(), reset.packSize()));
				captures.add(new ClientStateJournal.Capture(UpdatePlan.Root.GAME_DIR, reset.path(), reset.driftHash(), reset.driftSize(), false));
			}
			ClientStateJournal.StateEntry checkpoint = new ClientStateJournal.StateEntry(head.seq() + 1, "drift-reset-" + UUID.randomUUID(), ClientStateJournal.Kind.DRIFT_RESET,
					head.modpackId(), head.contentToken(), Instant.now(), ClientStateJournal.StateEntry.NO_RESTORE, head.state(), changes, captures);
			journal.append(checkpoint);
			return null;
		});
	}

	/** Silently resets client-side drift of an unchanged server-provided non-mod file so it never becomes an update prompt; the server changing the file stays a reviewable update. */
	private void resetDriftedServerFile(FileCache cache, ClientProjectionView.Snapshot projection, ModpackJsons.ModpackContentFields activeTarget,
			Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems, ModpackJsons.ModpackContentFields.ModpackContentItem item, List<DriftReset> driftResets)
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
		resetDriftedFile(cache, item, live, state, driftResets);
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
			if (mod != null) mods.add(new UpdatePlan.ModInfo(LogicalPath.normalize(item.file), item.sha1, item.size, mod.IDs(), mod.deps()));
		}
		return mods;
	}

	private List<UpdatePlan.ModInfo> inspectStandardMods(FileCache cache, ModFileCache modCache) throws IOException {
		if (!Files.isDirectory(storage.modsDirectory())) return List.of();
		List<UpdatePlan.ModInfo> mods = new ArrayList<>();
		try (Stream<Path> stream = Files.list(storage.modsDirectory())) {
			for (Path path : stream.filter(Files::isRegularFile).toList()) {
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
		Path inspectionDirectory = Files.createTempDirectory(storage.stagingDirectory(), "inspection-");
		try {
			for (var item : target.list.stream().filter(value -> ModpackPathPolicy.isActiveMod(LogicalPath.normalize(value.file), value.type)).toList()) {
				Path source = resolvedObject(item, projection, cache);
				if (source == null) continue;
				String logicalPath = LogicalPath.normalize(item.file);
				Path inspectionPath = inspectionDirectory.resolve(logicalPath).normalize();
				if (!inspectionPath.startsWith(inspectionDirectory)) throw new IOException("Mod inspection path escaped its temporary directory: " + item.file);
				materializeInspectionCopy(source, inspectionPath, item.size, item.sha1, cache);
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
			cache.overwriteCache(inspectionPath, sha1);
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
			FileInspection.Mod mod = modCache.getModOrNull(modPath, item.sha1, cache);
			if (mod != null && !Collections.disjoint(mod.services(), forceCopyServices)) forceCopyMods.add(item.file);
		}
		return forceCopyMods;
	}
}
