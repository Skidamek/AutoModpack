package pl.skidam.automodpack_core.update;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.PinnedMods;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.update.UpdatePlan.*;
import pl.skidam.automodpack_core.utils.HashUtils;

public final class UpdatePlanner {

	private UpdatePlanner() {}

	public record Input(
			ModpackJsons.ModpackContentFields installedManifest,
			ModpackJsons.ModpackContentFields targetManifest,
			Map<FileKey, FileState> files,
			Map<String, FileState> editableOverlays,
			Set<String> forceCopyServicePaths,
			List<ModInfo> targetMods,
			List<ModInfo> standardMods,
			List<NestedCopy> previousNestedCopies,
			List<NestedCopy> nestedCopies,
			SelectionContext selection,
			ClientConfigJsons.ClientConfigFieldsV3 plannedClientConfig,
			Map<String, FileState> consentedLocalModFiles) {
		public Input {
			if (installedManifest != null && consentedLocalModFiles != null && !consentedLocalModFiles.isEmpty())
				throw new IllegalArgumentException("First-install consent cannot be used after a modpack is installed");
			files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
			Map<String, FileState> normalizedOverlays = new TreeMap<>();
			for (var entry : editableOverlays.entrySet()) normalizedOverlays.put(LogicalPath.normalize(entry.getKey()), entry.getValue());
			editableOverlays = Collections.unmodifiableMap(normalizedOverlays);
			forceCopyServicePaths = Collections.unmodifiableSet(new LinkedHashSet<>(forceCopyServicePaths));
			targetMods = List.copyOf(targetMods);
			standardMods = List.copyOf(standardMods);
			previousNestedCopies = List.copyOf(previousNestedCopies);
			nestedCopies = List.copyOf(nestedCopies);
			Map<String, FileState> normalizedConsent = new TreeMap<>();
			for (var entry : (consentedLocalModFiles == null ? Map.<String, FileState>of() : consentedLocalModFiles).entrySet())
				normalizedConsent.put(LogicalPath.normalize(entry.getKey()), entry.getValue());
			consentedLocalModFiles = Collections.unmodifiableMap(normalizedConsent);
		}

		public Input(ModpackJsons.ModpackContentFields installedManifest, ModpackJsons.ModpackContentFields targetManifest, Map<FileKey, FileState> files,
				Map<String, FileState> editableOverlays, Set<String> forceCopyServicePaths, List<ModInfo> targetMods, List<ModInfo> standardMods,
				List<NestedCopy> previousNestedCopies, List<NestedCopy> nestedCopies, SelectionContext selection,
				ClientConfigJsons.ClientConfigFieldsV3 plannedClientConfig) {
			this(installedManifest, targetManifest, files, editableOverlays, forceCopyServicePaths, targetMods, standardMods, previousNestedCopies, nestedCopies, selection,
					plannedClientConfig, Map.of());
		}

	}

	public record SelectionContext(String previousModpackId, ModpackJsons.ModpackContentFields previousManifest, Map<String, FileState> previousEditableOverlays,
			ClientBaseline baseline, Set<String> availableBaselineObjects) {
		public SelectionContext(String previousModpackId, ModpackJsons.ModpackContentFields previousManifest) {
			this(previousModpackId, previousManifest, Map.of(), null, Set.of());
		}

		public SelectionContext(String previousModpackId, ModpackJsons.ModpackContentFields previousManifest, Map<String, FileState> previousEditableOverlays) {
			this(previousModpackId, previousManifest, previousEditableOverlays, null, Set.of());
		}

		public SelectionContext {
			previousEditableOverlays = Collections.unmodifiableMap(new TreeMap<>(previousEditableOverlays == null ? Map.of() : previousEditableOverlays));
			Set<String> normalizedObjects = new LinkedHashSet<>();
			for (String value : availableBaselineObjects == null ? Set.<String>of() : availableBaselineObjects)
				if (value != null) normalizedObjects.add(value.toLowerCase(Locale.ROOT));
			availableBaselineObjects = Collections.unmodifiableSet(normalizedObjects);
		}
	}

	public record RemovalInput(ModpackJsons.ModpackContentFields installedManifest, ClientBaseline baseline,
			Map<FileKey, FileState> files, Set<String> availableBaselineObjects, GeneratedCopyState generatedCopies, ClientConfigJsons.ClientConfigFieldsV3 plannedClientConfig) {
		public RemovalInput {
			files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
			Set<String> normalizedObjects = new LinkedHashSet<>();
			for (String value : availableBaselineObjects) if (value != null) normalizedObjects.add(value.toLowerCase(Locale.ROOT));
			availableBaselineObjects = Collections.unmodifiableSet(normalizedObjects);
		}
	}

	public static UpdatePlan planRemoval(RemovalInput input) {
		Objects.requireNonNull(input);
		ModpackJsons.ModpackContentFields installed = Objects.requireNonNull(input.installedManifest());
		ModpackId.requireValid(installed.modpackId);
		PackTarget packTarget = PackTarget.fromFlat(installed);
		OwnershipLedger ledger = OwnershipLedger.fromFields(installed.ownershipLedger);
		if (!installed.modpackId.equals(ledger.modpackId())) throw new IllegalArgumentException("Removal ledger modpack ID does not match installed modpack");
		if (input.generatedCopies() != null && (!installed.modpackId.equals(input.generatedCopies().modpackId())
				|| !packTarget.contentToken().equals(input.generatedCopies().contentToken())))
			throw new IllegalArgumentException("Removal generated-copy state identity is invalid");
		if (input.baseline() == null || !installed.modpackId.equals(input.baseline().modpackId()))
			throw new IllegalArgumentException("Removal baseline identity is invalid");
		if (input.plannedClientConfig() == null) throw new IllegalArgumentException("Removal client config is missing");

		Map<String, ClientBaseline.Entry> baselines = input.baseline().entriesByPath();
		PlanningSession session = new PlanningSession(input.files());
		session.restart(RestartReason.SELECTED_MODPACK);

		if (installed.list != null) for (var item : installed.list) {
			FileKey key = new FileKey(Root.PROJECTION, LogicalPath.normalize(item.file));
			FileState state = session.projected(key);
			if (state != null && state.regularFile() && hashesEqual(state.sha1(), item.sha1)) {
				session.delete(key, item.sha1);
			}
		}

		if (input.generatedCopies() != null) for (GeneratedCopyState.Entry generated : input.generatedCopies().entries()) {
			FileKey key = new FileKey(Root.GAME_DIR, generated.logicalPath());
			FileState state = session.projected(key);
			if (matches(state, generated.sha1(), generated.size())) {
				session.delete(key, generated.sha1());
				session.restart(RestartReason.FIXED_NESTED_MODS);
			}
		}

		for (OwnershipLedger.Entry ledgerEntry : ledger.entries().values()) {
			Optional<FileKey> candidateKey = managedCleanupKey(ledgerEntry.logicalPath());
			if (candidateKey.isEmpty()) continue;
			FileKey key = candidateKey.get();
			FileState state = session.projected(key);
			if (state == null || !state.regularFile() || state.sha1() == null) continue;
			OwnershipLedger.Content current = new OwnershipLedger.Content(state.sha1().toLowerCase(Locale.ROOT), state.size());
			if (!ledgerEntry.historicalHashes().contains(current)) continue;
			ClientBaseline.Entry baseline = baselines.get(ledgerEntry.logicalPath());
			restoreOwnedLiveFile(key, state, baseline, input.availableBaselineObjects(), true, session);
		}

		return session.finalState(installed.modpackId, packTarget, input.plannedClientConfig(), input.files(), installed, ledger, true, input.baseline(), List.of());
	}

	public static UpdatePlan plan(Input input) {
		Objects.requireNonNull(input);
		ModpackJsons.ModpackContentFields target = Objects.requireNonNull(input.targetManifest());
		ModpackId.requireValid(target.modpackId);
		PackTarget packTarget = PackTarget.fromFlat(target);
		OwnershipLedger ledger = OwnershipLedger.fromFields(target.ownershipLedger);
		if (!target.modpackId.equals(ledger.modpackId())) throw new IllegalArgumentException("Target ledger modpack ID does not match target");
		if (input.installedManifest() != null) PackTarget.fromFlat(input.installedManifest());
		if (target.list == null) throw new IllegalArgumentException("Target manifest list is missing");

		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems = sortedItems(target.list);
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> installedItems = input.installedManifest() == null
				|| input.installedManifest().list == null ? Map.of() : sortedItems(input.installedManifest().list);
		OwnershipLedger installedLedger = input.installedManifest() == null ? null : OwnershipLedger.fromFields(input.installedManifest().ownershipLedger);
		PlanningSession session = new PlanningSession(input.files());
		Map<String, ModInfo> targetModsByPath = modsByPath(input.targetMods());
		Map<String, ModInfo> standardModsByPath = modsByPath(input.standardMods());
		planConsentedLocalMods(input, standardModsByPath, session);
		planInstalledRemovals(input, target.modpackId, targetItems, installedItems, session);
		if (installedLedger != null)
			planLedgerCleanup(installedLedger, installedItems.keySet(), targetItems.keySet(), input.selection(), !input.installedManifest().modpackId.equals(target.modpackId), session);
		else
			planServerKnownCleanup(ledger, targetItems.keySet(), session);
		planSelectionChange(input, target, session);
		Set<String> forceCopyPaths = new HashSet<>(input.forceCopyServicePaths());
		Set<String> listedPins = listedPins(input);
		Set<String> protectedIds = PinnedMods.protectedIds(listedPins, input.standardMods().stream().map(ModInfo::ids).toList());
		planTargetInstalls(input, targetItems, forceCopyPaths, protectedIds, targetModsByPath, session);
		List<NestedCopy> generatedCopies = ownedNestedCopies(input.nestedCopies());
		planNestedCopies(input.previousNestedCopies(), generatedCopies, session);
		planDuplicates(target.modpackId, input.targetMods(), input.standardMods(), forceCopyPaths, installedLedger, session, listedPins);
		planBaselineCaptures(input.files(), session);
		return session.finalState(target.modpackId, packTarget, input.plannedClientConfig(), input.files(), target, ledger, false, null, generatedCopies);
	}

	/** Removes installed content the target no longer ships: its projection entry, its overlay, and its player-edited live copy. */
	private static void planInstalledRemovals(Input input, String targetModpackId, Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems,
			Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> installedItems, PlanningSession session) {
		for (var entry : installedItems.entrySet()) {
			if (targetItems.containsKey(entry.getKey())) continue;
			FileKey modpackKey = new FileKey(Root.PROJECTION, LogicalPath.normalize(entry.getKey()));
			session.delete(modpackKey, null);
			if (input.installedManifest() != null && input.installedManifest().modpackId.equals(targetModpackId)) {
				FileKey overlayKey = new FileKey(Root.OVERLAY, LogicalPath.normalize(entry.getKey()));
				if (session.has(overlayKey)) session.delete(overlayKey, session.projected(overlayKey).sha1());
			}
			FileKey liveKey = liveKey(entry.getValue());
			FileState live = session.projected(liveKey);
			FileState previousOverlay = input.selection() == null ? null : input.selection().previousEditableOverlays().get(entry.getKey());
			if (previousOverlay != null && previousOverlay.regularFile() && live != null && hashesEqual(live.sha1(), previousOverlay.sha1())) {
				session.delete(liveKey, previousOverlay.sha1());
				noteStandardModsMutation(liveKey, true, session);
			}
		}
		for (FileKey key : session.projectedKeys()) {
			if (key.root() != Root.PROJECTION || targetItems.containsKey(key.relativePath()) || session.hasOperation(key)) continue;
			FileState extra = session.projected(key);
			session.delete(key, extra == null ? null : extra.sha1());
		}
	}

	/** Records why this run touches the selection seam: a group change or a first selection of the modpack. */
	private static void planSelectionChange(Input input, ModpackJsons.ModpackContentFields target, PlanningSession session) {
		if (input.installedManifest() != null && !Objects.equals(input.installedManifest().selectedGroups, target.selectedGroups))
			session.restart(RestartReason.CHANGED_GROUP_SELECTION);
		if (input.installedManifest() == null || isSelectionChange(input.selection(), target.modpackId)) session.restart(RestartReason.SELECTED_MODPACK);
	}

	/** Installs every target manifest item into the projection, its overlay, and — when not protected from the player's mods directory — the live copy. */
	private static void planTargetInstalls(Input input, Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems, Set<String> forceCopyPaths,
			Set<String> protectedIds, Map<String, ModInfo> targetModsByPath, PlanningSession session) {
		for (var item : targetItems.values()) {
			String relative = LogicalPath.normalize(item.file);
			boolean activeMod = ModpackPathPolicy.isActiveMod(relative, item.type);
			FileKey modpackKey = new FileKey(Root.PROJECTION, relative);
			FileState existing = session.projected(modpackKey);
			FileState overlay = item.editable ? input.editableOverlays().get(relative) : null;
			if (overlay != null && overlay.regularFile() && !matches(session.projected(new FileKey(Root.OVERLAY, relative)), overlay.sha1(), overlay.size()))
				session.install(new FileKey(Root.OVERLAY, relative), overlay.sha1(), overlay.size());
			if (overlay == null && session.has(new FileKey(Root.OVERLAY, relative)))
				session.delete(new FileKey(Root.OVERLAY, relative), session.projected(new FileKey(Root.OVERLAY, relative)).sha1());
			if (!matches(existing, item.sha1, item.size)) session.install(modpackKey, item.sha1, item.size);

			boolean copyToLive = !PinnedMods.protects(protectedIds, idsForPath(targetModsByPath, relative)) && (!activeMod || forceCopyPaths.contains(relative) || overlay != null);
			FileKey liveKey = liveKey(item);
			if (copyToLive) {
				FileState live = session.projected(liveKey);
				if (overlay != null && !overlay.regularFile()) {
					if (live != null) session.delete(liveKey, live.sha1());
				} else {
					String liveHash = overlay == null ? item.sha1 : overlay.sha1();
					long liveSize = overlay == null ? item.size : overlay.size();
					if (!matches(live, liveHash, liveSize)) {
						FileState consented = input.consentedLocalModFiles().get(relative);
						session.install(liveKey, liveHash, liveSize, consented == null ? null : consented.sha1());
						if (activeMod) session.restart(RestartReason.CORRECTED_FILE_LOCATIONS);
					}
				}
			}
		}
	}

	private static void planConsentedLocalMods(Input input, Map<String, ModInfo> standardModsByPath, PlanningSession session) {
		if (input.installedManifest() != null) {
			if (!input.consentedLocalModFiles().isEmpty()) throw new IllegalArgumentException("First-install consent cannot be used after a modpack is installed");
			return;
		}
		if (input.consentedLocalModFiles().isEmpty()) return;
		Set<String> listedPins = listedPins(input);
		for (var entry : input.consentedLocalModFiles().entrySet()) {
			String relative = LogicalPath.normalize(entry.getKey());
			Path path = Path.of(relative);
			if (path.getNameCount() != 2 || !path.getName(0).toString().equals(ModpackPathPolicy.MODS_ROOT))
				throw new IllegalArgumentException("First-install consent path must be a direct mods child: " + relative);
			FileState observed = entry.getValue();
			if (observed == null || !observed.regularFile() || !HashUtils.isSha1(observed.sha1()) || observed.size() < 0)
				throw new IllegalArgumentException("First-install consent file metadata is invalid: " + relative);
			if (PinnedMods.matches(listedPins, idsForPath(standardModsByPath, relative))) continue;
			FileKey key = new FileKey(Root.GAME_DIR, relative);
			FileState current = session.projected(key);
			if (!matches(current, observed.sha1(), observed.size())) throw new IllegalArgumentException("First-install consent file changed after scanning: " + relative);
			session.preserve(new Preservation(Root.GAME_DIR, relative, observed.sha1().toLowerCase(Locale.ROOT), observed.size(), PreservationProof.PLAYER_CONSENT));
			session.delete(key, observed.sha1());
			session.restart(RestartReason.REMOVED_LOCAL_MODS);
		}
	}

	private static ChangeSet consequences(List<Operation> operations, Map<FileKey, FileState> originalFiles, ModpackJsons.ModpackContentFields target,
			OwnershipLedger ledger, Set<RestartReason> restartReasons, boolean removal, ClientBaseline baseline) {
		Map<FileKey, Operation> operationsByFile = operations.stream().collect(Collectors.toMap(operation -> new FileKey(operation.root(), operation.relativePath()), Function.identity()));
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetFiles = target.list == null ? Map.of() : sortedItems(target.list);
		List<ChangeSet.Change> changes = new ArrayList<>();
		for (Operation operation : operations) {
			FileKey key = new FileKey(operation.root(), operation.relativePath());
			FileState before = originalFiles.get(key);
			ModpackJsons.ModpackContentFields.ModpackContentItem after = targetFiles.get(operation.relativePath());
			OwnershipLedger.Entry ownership = ledger.entries().get(operation.relativePath());
			ChangeSet.Kind kind = operation.operation() == OperationType.DELETE
					? ChangeSet.Kind.REMOVED
					: before == null || !before.regularFile() ? ChangeSet.Kind.ADDED : ChangeSet.Kind.MODIFIED;
			String beforeHash = before == null || !HashUtils.isSha1(before.sha1()) ? null : before.sha1();
			String afterHash = operation.operation() == OperationType.DELETE ? null : operation.expectedObjectHash();
			String contentKind = after == null ? null : after.type;
			List<String> featureIds = ownership == null ? List.of() : List.copyOf(ownership.historicalGroupIds());
			long size = operation.operation() == OperationType.DELETE ? before == null ? 0 : Math.max(0, before.size()) : operation.expectedSize();
			Long beforeSize = kind == ChangeSet.Kind.REMOVED ? null : before != null && before.regularFile() ? Math.max(0, before.size()) : null;
			changes.add(new ChangeSet.Change(operation.relativePath(), kind,
					List.of(new ChangeSet.Occurrence(operation.root().name(), operation.relativePath(), size, beforeSize, beforeHash, afterHash, contentKind, featureIds, List.of()))));
		}

		Set<String> targetPaths = targetFiles.keySet();
		Map<String, ClientBaseline.Entry> baselineEntries = removal ? baseline.entriesByPath() : Map.of();
		for (OwnershipLedger.Entry ledgerEntry : ledger.entries().values()) {
			if (!removal && targetPaths.contains(ledgerEntry.logicalPath())) continue;
			Optional<FileKey> optionalKey = managedCleanupKey(ledgerEntry.logicalPath());
			if (optionalKey.isEmpty()) continue;
			FileKey key = optionalKey.get();
			FileState current = originalFiles.get(key);
			if (current == null || operationsByFile.containsKey(key)) continue;
			if (removal && consequenceBaselineMatches(current, baselineEntries.get(ledgerEntry.logicalPath()))) continue;
			ChangeSet.Kind kind;
			if (!current.regularFile()) {
				kind = ChangeSet.Kind.UNSAFE;
			} else if (!HashUtils.isSha1(current.sha1()) || current.size() < 0) {
				kind = ChangeSet.Kind.PRESERVED_UNAVAILABLE;
			} else {
				OwnershipLedger.Content content = new OwnershipLedger.Content(current.sha1().toLowerCase(Locale.ROOT), current.size());
				kind = ledgerEntry.historicalHashes().contains(content)
						? removal ? ChangeSet.Kind.PRESERVED_UNAVAILABLE : ChangeSet.Kind.PRESERVED_OUTSIDE
						: ChangeSet.Kind.PRESERVED_CHANGED;
			}
			String beforeHash = HashUtils.isSha1(current.sha1()) ? current.sha1() : null;
			changes.add(new ChangeSet.Change(key.relativePath(), kind, List.of(new ChangeSet.Occurrence(key.root().name(), key.relativePath(), Math.max(0, current.size()),
					null, beforeHash, null, null, List.copyOf(ledgerEntry.historicalGroupIds()), List.of()))));
		}

		List<ChangeSet.Effect> effects = restartReasons.stream().map(reason -> ChangeSet.Effect.restart(reason.name())).toList();
		return ChangeSet.of(changes, effects);
	}

	private static boolean consequenceBaselineMatches(FileState current, ClientBaseline.Entry baseline) {
		return baseline != null && !baseline.absent() && current.regularFile() && baseline.size() == current.size() && baseline.objectHash().equalsIgnoreCase(current.sha1());
	}

	private static void planBaselineCaptures(Map<FileKey, FileState> original, PlanningSession session) {
		Map<FileKey, BaselineCapture> planned = new HashMap<>();
		for (Operation operation : session.operations()) {
			if ((operation.operation() != OperationType.INSTALL_OBJECT && operation.operation() != OperationType.DELETE)
					|| operation.root() != Root.GAME_DIR)
				continue;
			FileKey key = new FileKey(operation.root(), LogicalPath.normalize(operation.relativePath()));
			FileState previous = original.get(key);
			if (previous != null && (!previous.regularFile() || previous.sha1() == null || previous.size() < 0))
				throw new IllegalArgumentException("Cannot capture a safe baseline for live path: " + key.relativePath());
			BaselineCapture capture = previous == null
					? new BaselineCapture(key.root(), key.relativePath(), "", -1, true)
					: new BaselineCapture(key.root(), key.relativePath(), previous.sha1().toLowerCase(Locale.ROOT), previous.size(), false);
			planned.putIfAbsent(key, capture);
		}
		session.captures().addAll(planned.values());
	}

	private static void planLedgerCleanup(OwnershipLedger ledger, Set<String> installedPaths, Set<String> targetPaths, SelectionContext selection, boolean preserveReplacedBytes,
			PlanningSession session) {
		Map<String, ClientBaseline.Entry> baselines = selection == null || selection.baseline() == null ? Map.of() : selection.baseline().entriesByPath();
		for (OwnershipLedger.Entry entry : ledger.entries().values()) {
			if (!installedPaths.contains(entry.logicalPath()) || targetPaths.contains(entry.logicalPath())) continue;
			Optional<FileKey> candidateKey = managedCleanupKey(entry.logicalPath());
			if (candidateKey.isEmpty()) continue;
			FileKey key = candidateKey.get();
			FileState state = session.projected(key);
			if (state == null || !state.regularFile() || state.sha1() == null) continue;
			OwnershipLedger.Content content = new OwnershipLedger.Content(state.sha1().toLowerCase(Locale.ROOT), state.size());
			if (!entry.historicalHashes().contains(content)) continue;
			ClientBaseline.Entry baseline = baselines.get(entry.logicalPath());
			if (selection == null || selection.baseline() == null) {
				session.preserve(new Preservation(key.root(), key.relativePath(), state.sha1().toLowerCase(Locale.ROOT), state.size()));
				session.delete(key, state.sha1());
				noteStandardModsMutation(key, true, session);
				continue;
			}
			restoreOwnedLiveFile(key, state, baseline, selection.availableBaselineObjects(), preserveReplacedBytes, session);
		}
	}

	private static boolean baselineMatches(FileState state, ClientBaseline.Entry baseline) {
		return !baseline.absent() && matches(state, baseline.objectHash(), baseline.size());
	}

	private static boolean restoreOwnedLiveFile(FileKey key, FileState state, ClientBaseline.Entry baseline,
			Set<String> availableBaselineObjects, boolean preserveReplacedBytes, PlanningSession session) {
		if (baseline != null && baselineMatches(state, baseline)) return false;
		String currentHash = state.sha1().toLowerCase(Locale.ROOT);
		// Callers prove these exact bytes belong to the installed selection before a missing
		// baseline is interpreted as no pre-install file to restore.
		if (baseline == null || baseline.absent()) {
			session.preserve(new Preservation(key.root(), key.relativePath(), currentHash, state.size()));
			session.delete(key, currentHash);
			noteStandardModsMutation(key, true, session);
			return true;
		}
		String baselineHash = baseline.objectHash();
		if (!availableBaselineObjects.contains(baselineHash)) return false;
		if (preserveReplacedBytes) session.preserve(new Preservation(key.root(), key.relativePath(), currentHash, state.size()));
		session.install(key, baselineHash, baseline.size(), currentHash);
		noteStandardModsMutation(key, false, session);
		return true;
	}

	private static void planServerKnownCleanup(OwnershipLedger ledger, Set<String> targetPaths, PlanningSession session) {
		for (OwnershipLedger.Entry entry : ledger.entries().values()) {
			if (entry.currentStatus() != OwnershipLedger.Status.TOMBSTONE || targetPaths.contains(entry.logicalPath())) continue;
			Optional<FileKey> candidateKey = managedCleanupKey(entry.logicalPath());
			if (candidateKey.isEmpty()) continue;
			FileKey key = candidateKey.get();
			FileState state = session.projected(key);
			if (state == null || !state.regularFile() || state.sha1() == null) continue;
			OwnershipLedger.Content content = new OwnershipLedger.Content(state.sha1().toLowerCase(Locale.ROOT), state.size());
			if (!entry.historicalHashes().contains(content)) continue;
			session.preserve(new Preservation(key.root(), key.relativePath(), state.sha1().toLowerCase(Locale.ROOT), state.size(), PreservationProof.SERVER_LEDGER));
			session.delete(key, state.sha1());
			noteStandardModsMutation(key, true, session);
		}
	}

	private static void noteStandardModsMutation(FileKey key, boolean deleted, PlanningSession session) {
		if (key.root() != Root.GAME_DIR || !ModpackPathPolicy.isModPath(key.relativePath())) return;
		session.restart(deleted ? RestartReason.REMOVED_STANDARD_MODS : RestartReason.CORRECTED_FILE_LOCATIONS);
	}

	public static Optional<FileKey> managedCleanupKey(String logicalPath) {
		final String normalized;
		try {
			normalized = LogicalPath.normalize(logicalPath);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
		if (ModpackPathPolicy.isPlayerLocal(normalized)) return Optional.empty();
		return Optional.of(new FileKey(Root.GAME_DIR, normalized));
	}

	private static boolean isSelectionChange(SelectionContext selection, String targetModpackId) {
		return selection != null && selection.previousModpackId() != null && !selection.previousModpackId().isBlank()
				&& !selection.previousModpackId().equals(targetModpackId);
	}

	private static void planNestedCopies(List<NestedCopy> previousCopies, List<NestedCopy> copies, PlanningSession session) {
		Map<String, NestedCopy> previousByPath = previousCopies.stream().collect(Collectors.toMap(NestedCopy::relativePath, Function.identity(), (first, second) -> {
			throw new IllegalArgumentException("Duplicate previous generated-copy path: " + first.relativePath());
		}, TreeMap::new));
		Set<String> targetPaths = copies.stream().map(NestedCopy::relativePath).collect(Collectors.toSet());
		for (NestedCopy previous : previousCopies.stream().sorted(Comparator.comparing(NestedCopy::relativePath)).toList()) {
			if (targetPaths.contains(previous.relativePath())) continue;
			FileKey key = new FileKey(Root.GAME_DIR, LogicalPath.normalize(previous.relativePath()));
			FileState current = session.projected(key);
			if (matches(current, previous.sha1(), previous.size())) {
				session.delete(key, previous.sha1());
				session.restart(RestartReason.FIXED_NESTED_MODS);
			}
		}
		for (NestedCopy copy : copies) {
			FileKey key = new FileKey(Root.GAME_DIR, LogicalPath.normalize(copy.relativePath()));
			FileState current = session.projected(key);
			if (!matches(current, copy.sha1(), copy.size())) {
				NestedCopy previous = previousByPath.get(copy.relativePath());
				if (current != null && (previous == null || !matches(current, previous.sha1(), previous.size()))) {
					continue;
				}
				String expectedExistingHash = previous == null ? null : previous.sha1();
				session.install(key, copy.sha1(), copy.size(), expectedExistingHash);
				session.restart(RestartReason.FIXED_NESTED_MODS);
			}
		}
	}

	private static List<NestedCopy> ownedNestedCopies(List<NestedCopy> copies) {
		Set<String> generatedIds = new HashSet<>();
		List<NestedCopy> owned = new ArrayList<>();
		for (NestedCopy copy : copies.stream().sorted(Comparator.comparing(NestedCopy::relativePath)).toList()) {
			if (copy.ids().stream().anyMatch(generatedIds::contains)) continue;
			owned.add(copy);
			generatedIds.addAll(copy.ids());
		}
		return List.copyOf(owned);
	}

	private static void planDuplicates(String modpackId, List<ModInfo> targetMods, List<ModInfo> standardMods, Set<String> forceCopyPaths,
			OwnershipLedger installedLedger, PlanningSession session, Set<String> listedPins) {
		List<ModInfo> sortedTarget = targetMods.stream().filter(mod -> session.has(new FileKey(Root.PROJECTION, LogicalPath.normalize(mod.relativePath()))))
				.sorted(Comparator.comparing(ModInfo::relativePath)).toList();
		List<ModInfo> sortedStandard = standardMods.stream().filter(mod -> session.has(new FileKey(Root.GAME_DIR, LogicalPath.normalize(mod.relativePath()))))
				.sorted(Comparator.comparing(ModInfo::relativePath)).toList();
		Map<ModInfo, ModInfo> duplicates = new LinkedHashMap<>();
		for (ModInfo target : sortedTarget) {
			if (forceCopyPaths.contains(LogicalPath.normalize(target.relativePath()))) continue;
			sortedStandard.stream().filter(standard -> intersects(target.ids(), standard.ids())).findFirst().ifPresent(standard -> duplicates.put(target, standard));
		}
		Set<ModInfo> keep = new HashSet<>();
		for (ModInfo standard : sortedStandard) if (!duplicates.containsValue(standard)) addDependencies(standard, sortedStandard, keep);
		Set<String> idsToKeep = keep.stream().flatMap(mod -> mod.ids().stream()).collect(Collectors.toSet());

		for (var duplicate : duplicates.entrySet()) {
			ModInfo target = duplicate.getKey();
			ModInfo standard = duplicate.getValue();
			String targetPath = LogicalPath.normalize(target.relativePath());
			String standardPath = LogicalPath.normalize(standard.relativePath());
			if (PinnedMods.matches(listedPins, standard.ids())) continue;
			FileKey oldKey = new FileKey(Root.GAME_DIR, standardPath);
			boolean owned = isOwned(standard, standardPath, installedLedger);
			boolean keepStandard = target.ids().stream().anyMatch(idsToKeep::contains);
			FileKey targetKey = new FileKey(Root.GAME_DIR, targetPath);
			boolean targetAlreadyMatches = matches(session.projected(targetKey), target.sha1(), target.size());
			boolean sourceNeedsDisposition = !oldKey.equals(targetKey) || !keepStandard || !targetAlreadyMatches;
			if (sourceNeedsDisposition) session.conflicts().add(conflict(modpackId, targetPath, target, standardPath, standard, owned ? ConflictAction.REMOVE_OWNED : ConflictAction.PRESERVE_LOCAL));
			if (keepStandard) {
				if (!targetAlreadyMatches) {
					session.install(targetKey, target.sha1(), target.size(),
							oldKey.equals(targetKey) ? standard.sha1() : null);
					session.restart(RestartReason.REMOVED_DUPLICATE_MODS);
				}
				if (!oldKey.equals(targetKey)) session.delete(oldKey, standard.sha1());
			} else {
				session.delete(oldKey, standard.sha1());
				session.restart(RestartReason.REMOVED_DUPLICATE_MODS);
			}
		}
	}

	private static boolean isOwned(ModInfo standard, String standardPath, OwnershipLedger ledger) {
		if (ledger == null) return false;
		OwnershipLedger.Entry entry = ledger.entries().get(standardPath);
		return entry != null && entry.historicalHashes().contains(new OwnershipLedger.Content(standard.sha1().toLowerCase(Locale.ROOT), standard.size()));
	}

	private static Conflict conflict(String modpackId, String targetPath, ModInfo target, String standardPath, ModInfo standard, ConflictAction action) {
		String identity = conflictId(target, targetPath, standard, standardPath);
		Set<String> ids = new TreeSet<>(target.ids());
		ids.addAll(standard.ids());
		return new Conflict(modpackId, identity, ids, standardPath, standard.sha1(), standard.size(), targetPath, target.sha1(), target.size(), action);
	}

	private static String conflictId(ModInfo target, String targetPath, ModInfo standard, String standardPath) {
		String value = String.join("\n", targetPath, target.sha1().toLowerCase(Locale.ROOT), standardPath,
				standard.sha1().toLowerCase(Locale.ROOT), String.join(",", new TreeSet<>(target.ids()).stream().map(id -> id.toLowerCase(Locale.ROOT)).toList()),
				String.join(",", new TreeSet<>(standard.ids()).stream().map(id -> id.toLowerCase(Locale.ROOT)).toList()));
		return HashUtils.sha1(value);
	}

	private static void addDependencies(ModInfo mod, List<ModInfo> all, Set<ModInfo> result) {
		if (!result.add(mod)) return;
		for (String dependency : mod.dependencies())
			for (ModInfo candidate : all)
				if (candidate.ids().stream().anyMatch(id -> id.equalsIgnoreCase(dependency))) addDependencies(candidate, all, result);
	}

	private static Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> sortedItems(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> items) {
		return items.stream().sorted(Comparator.comparing(UpdatePlanner::normalizedManifestPath)).collect(Collectors.toMap(UpdatePlanner::normalizedManifestPath, Function.identity(),
				(first, second) -> {
					throw new IllegalArgumentException("Duplicate normalized manifest path: " + first.file);
				}, LinkedHashMap::new));
	}

	private static String normalizedManifestPath(ModpackJsons.ModpackContentFields.ModpackContentItem item) {
		if (item == null) throw new IllegalArgumentException("Manifest item is incomplete");
		String normalized = LogicalPath.normalize(item.file);
		if (!ModpackPathPolicy.isValidTypeAndPath(normalized, item.type))
			throw new IllegalArgumentException("Invalid manifest type/path combination: " + item.type + " " + item.file);
		return normalized;
	}

	private static FileKey liveKey(ModpackJsons.ModpackContentFields.ModpackContentItem item) {
		String relative = LogicalPath.normalize(item.file);
		return new FileKey(Root.GAME_DIR, relative);
	}

	/**
	 * The mutable accumulator for one planning run. Projected state and operations move in lockstep — every operation
	 * immediately updates the projection — so they live here instead of being threaded through passes as parallel parameters.
	 */
	static final class PlanningSession {
		private final Map<FileKey, FileState> projected;
		private final Set<FileKey> projectedScope;
		private final Map<FileKey, Operation> operations = new HashMap<>();
		private final EnumSet<RestartReason> restartReasons = EnumSet.noneOf(RestartReason.class);
		private final List<Preservation> preservations = new ArrayList<>();
		private final List<BaselineCapture> baselineCaptures = new ArrayList<>();
		private final List<Conflict> conflicts = new ArrayList<>();

		PlanningSession(Map<FileKey, FileState> files) {
			projected = new HashMap<>(files);
			projectedScope = new HashSet<>(files.keySet());
		}

		FileState projected(FileKey key) {
			return projected.get(key);
		}

		boolean has(FileKey key) {
			return projected.containsKey(key);
		}

		boolean hasOperation(FileKey key) {
			return operations.containsKey(key);
		}

		Set<FileKey> projectedKeys() {
			return Set.copyOf(projected.keySet());
		}

		List<Operation> operations() {
			return List.copyOf(operations.values());
		}

		List<Preservation> preservations() {
			return preservations;
		}

		List<BaselineCapture> captures() {
			return baselineCaptures;
		}

		List<Conflict> conflicts() {
			return conflicts;
		}

		void restart(RestartReason reason) {
			restartReasons.add(reason);
		}

		void preserve(Preservation preservation) {
			preservations.add(preservation);
		}

		void install(FileKey key, String hash, long size) {
			install(key, hash, size, null);
		}

		void install(FileKey key, String hash, long size, String expectedExistingHash) {
			String safeExpectedExistingHash = expectedExistingHash;
			if (safeExpectedExistingHash == null && key.root() == Root.GAME_DIR) safeExpectedExistingHash = expectedExistingHash(key);
			operations.put(key, new Operation(key.root(), key.relativePath(), OperationType.INSTALL_OBJECT, hash, size, safeExpectedExistingHash));
			projected.put(key, new FileState(hash, size, true));
		}

		void delete(FileKey key, String expectedHash) {
			String safeExpectedHash = expectedHash != null ? expectedHash : expectedExistingHash(key);
			operations.put(key, new Operation(key.root(), key.relativePath(), OperationType.DELETE, null, -1, safeExpectedHash));
			projected.remove(key);
		}

		private String expectedExistingHash(FileKey key) {
			Operation previous = operations.get(key);
			if (previous != null) return previous.expectedExistingHash();
			FileState existing = projected.get(key);
			return existing != null && existing.regularFile() ? existing.sha1() : null;
		}

		/** The canonical plan: ordered operations, the projected final state after every operation, and the sorted review consequences. */
		UpdatePlan finalState(String modpackId, PackTarget packTarget, ClientConfigJsons.ClientConfigFieldsV3 plannedClientConfig, Map<FileKey, FileState> originalFiles,
				ModpackJsons.ModpackContentFields manifest, OwnershipLedger ledger, boolean removal, ClientBaseline baseline, List<NestedCopy> generatedCopies) {
			List<Operation> ordered = operations.values().stream().sorted(Operation.ORDER).toList();
			projectedScope.addAll(operations.keySet());
			List<ProjectedFile> finalState = projectedScope.stream().sorted(FileKey.ORDER).map(key -> {
				FileState state = projected.get(key);
				return state == null || !state.regularFile()
						? new ProjectedFile(key.root(), key.relativePath(), false, null, -1)
						: new ProjectedFile(key.root(), key.relativePath(), true, state.sha1(), state.size());
			}).toList();
			ChangeSet consequences = consequences(ordered, originalFiles, manifest, ledger, restartReasons, removal, baseline);
			return new UpdatePlan(modpackId, packTarget, ordered, finalState, plannedClientConfig, restartReasons,
					preservations.stream().sorted(Comparator.comparing((Preservation preservation) -> preservation.root().ordinal()).thenComparing(Preservation::relativePath)).toList(),
					baselineCaptures.stream().sorted(Comparator.comparing((BaselineCapture capture) -> capture.root().ordinal()).thenComparing(BaselineCapture::relativePath)).toList(),
					conflicts.stream().sorted(Comparator.comparing(Conflict::conflictId)).toList(), generatedCopies, consequences);
		}
	}

	private static boolean matches(FileState state, String hash, long size) {
		return state != null && state.regularFile() && state.size() == size && hashesEqual(state.sha1(), hash);
	}

	private static boolean hashesEqual(String first, String second) {
		return first != null && second != null && first.equalsIgnoreCase(second);
	}

	private static boolean intersects(Set<String> first, Set<String> second) {
		return first.stream().anyMatch(second::contains);
	}

	private static Set<String> listedPins(Input input) {
		return input.plannedClientConfig() == null ? Set.of() : PinnedMods.index(input.plannedClientConfig().pinnedModIds);
	}

	/** Indexes scanned mods by normalized logical path once per plan; the first mod on a path wins, matching the linear-scan lookups this replaces. */
	private static Map<String, ModInfo> modsByPath(List<ModInfo> mods) {
		Map<String, ModInfo> byPath = new HashMap<>();
		for (ModInfo mod : mods) byPath.putIfAbsent(LogicalPath.normalize(mod.relativePath()), mod);
		return byPath;
	}

	private static Set<String> idsForPath(Map<String, ModInfo> modsByPath, String relative) {
		ModInfo mod = modsByPath.get(relative);
		return mod == null ? Set.of() : mod.ids();
	}

}
