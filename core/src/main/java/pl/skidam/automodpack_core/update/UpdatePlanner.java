package pl.skidam.automodpack_core.update;

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

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackContentType;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.update.UpdatePlan.*;
import pl.skidam.automodpack_core.utils.HashUtils;

public final class UpdatePlanner {
	private static final Comparator<Operation> OPERATION_ORDER = Comparator.comparing((Operation operation) -> operation.operation().ordinal())
			.thenComparing(operation -> operation.root().ordinal()).thenComparing(Operation::relativePath);
	private static final Comparator<FileKey> FILE_KEY_ORDER = Comparator.comparing((FileKey key) -> key.root().ordinal()).thenComparing(FileKey::relativePath);

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
			ClientConfigJsons.ClientConfigFieldsV3 plannedClientConfig) {
		public Input {
			files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
			Map<String, FileState> normalizedOverlays = new TreeMap<>();
			for (var entry : editableOverlays.entrySet()) normalizedOverlays.put(normalize(entry.getKey()), entry.getValue());
			editableOverlays = Collections.unmodifiableMap(normalizedOverlays);
			forceCopyServicePaths = Collections.unmodifiableSet(new LinkedHashSet<>(forceCopyServicePaths));
			targetMods = List.copyOf(targetMods);
			standardMods = List.copyOf(standardMods);
			previousNestedCopies = List.copyOf(previousNestedCopies);
			nestedCopies = List.copyOf(nestedCopies);
		}

	}

	public record SelectionContext(String previousModpackId, ModpackJsons.ModpackContentFields previousManifest, Map<String, FileState> previousEditableOverlays) {
		public SelectionContext(String previousModpackId, ModpackJsons.ModpackContentFields previousManifest) {
			this(previousModpackId, previousManifest, Map.of());
		}

		public SelectionContext {
			previousEditableOverlays = Collections.unmodifiableMap(new TreeMap<>(previousEditableOverlays == null ? Map.of() : previousEditableOverlays));
		}
	}

	public record RemovalInput(ModpackJsons.ModpackContentFields installedManifest, ClientStorageJsons.ClientBaselineFields baseline,
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
		GenerationTarget generationTarget = GenerationTarget.fromFlat(installed);
		OwnershipLedger ledger = OwnershipLedger.fromFields(installed.ownershipLedger);
		if (!installed.modpackId.equals(ledger.modpackId())) throw new IllegalArgumentException("Removal ledger modpack ID does not match installed modpack");
		if (input.generatedCopies() != null && (!installed.modpackId.equals(input.generatedCopies().modpackId())
				|| !generationTarget.targetGenerationId().equals(input.generatedCopies().generationId())))
			throw new IllegalArgumentException("Removal generated-copy state identity is invalid");
		if (input.baseline() == null || !installed.modpackId.equals(input.baseline().modpackId) || input.baseline().entries == null)
			throw new IllegalArgumentException("Removal baseline identity is invalid");
		if (input.plannedClientConfig() == null) throw new IllegalArgumentException("Removal client config is missing");

		Map<String, ClientStorageJsons.ClientBaselineFields.EntryFields> baselines = new TreeMap<>();
		for (var entry : input.baseline().entries) {
			if (entry == null || entry.logicalPath == null || !normalize(entry.logicalPath).equals(entry.logicalPath)
					|| baselines.put(entry.logicalPath, entry) != null)
				throw new IllegalArgumentException("Removal baseline contains duplicate or incomplete entries");
			if (entry.absent) {
				if (entry.objectHash == null || !entry.objectHash.isEmpty() || entry.size != -1)
					throw new IllegalArgumentException("Absent removal baseline contains file metadata");
			} else if (!HashUtils.isSha1(entry.objectHash) || entry.size < 0) {
				throw new IllegalArgumentException("Removal baseline file metadata is invalid");
			}
		}
		Map<FileKey, FileState> projected = new HashMap<>(input.files());
		Set<FileKey> projectedScope = new HashSet<>(input.files().keySet());
		Map<FileKey, Operation> operations = new HashMap<>();
		List<Preservation> preservations = new ArrayList<>();
		EnumSet<RestartReason> restartReasons = EnumSet.noneOf(RestartReason.class);

		if (installed.list != null) for (var item : installed.list) {
			FileKey key = new FileKey(Root.PROJECTION, normalize(item.file));
			FileState state = projected.get(key);
			if (state != null && state.regularFile() && hashesEqual(state.sha1(), item.sha1)) {
				delete(operations, projected, key, item.sha1);
				restartReasons.add(RestartReason.REMOVED_NON_MODPACK_FILES);
			}
		}

		if (input.generatedCopies() != null) for (GeneratedCopyState.Entry generated : input.generatedCopies().entries()) {
			FileKey key = new FileKey(Root.GAME_DIR, generated.logicalPath());
			FileState state = projected.get(key);
			if (matches(state, generated.sha1(), generated.size())) {
				delete(operations, projected, key, generated.sha1());
				restartReasons.add(RestartReason.FIXED_NESTED_MODS);
			}
		}

		for (OwnershipLedger.Entry ledgerEntry : ledger.entries().values()) {
			Optional<FileKey> candidateKey = managedCleanupKey(ledgerEntry.logicalPath());
			if (candidateKey.isEmpty()) continue;
			FileKey key = candidateKey.get();
			FileState state = projected.get(key);
			if (state == null || !state.regularFile() || state.sha1() == null) continue;
			OwnershipLedger.Content current = new OwnershipLedger.Content(state.sha1().toLowerCase(Locale.ROOT), state.size());
			if (!ledgerEntry.historicalHashes().contains(current)) continue;
			ClientStorageJsons.ClientBaselineFields.EntryFields baseline = baselines.get(ledgerEntry.logicalPath());
			if (baseline == null) continue;
			if (baseline.absent) {
				preservations.add(new Preservation(key.root(), key.relativePath(), current.sha1(), current.size()));
				delete(operations, projected, key, current.sha1());
				restartReasons.add(RestartReason.APPLIED_SERVER_DELETIONS);
				continue;
			}
			if (!HashUtils.isSha1(baseline.objectHash) || baseline.size < 0) continue;
			String baselineHash = HashUtils.normalizeSha1(baseline.objectHash);
			if (!input.availableBaselineObjects().contains(baselineHash)) continue;
			if (matches(state, baselineHash, baseline.size)) continue;
			operations.put(key, new Operation(key.root(), key.relativePath(), OperationType.INSTALL_OBJECT, baselineHash, baseline.size, current.sha1()));
			projected.put(key, new FileState(baselineHash, baseline.size, true));
			restartReasons.add(RestartReason.APPLIED_SERVER_DELETIONS);
		}

		List<Operation> ordered = operations.values().stream().sorted(OPERATION_ORDER).toList();
		projectedScope.addAll(operations.keySet());
		List<ProjectedFile> finalState = projectedScope.stream().sorted(FILE_KEY_ORDER).map(key -> {
			FileState state = projected.get(key);
			return state == null || !state.regularFile()
					? new ProjectedFile(key.root(), key.relativePath(), false, null, -1)
					: new ProjectedFile(key.root(), key.relativePath(), true, state.sha1(), state.size());
		}).toList();
		return new UpdatePlan(installed.modpackId, generationTarget, ordered, finalState, input.plannedClientConfig(), restartReasons,
				preservations.stream().sorted(Comparator.comparing((Preservation preservation) -> preservation.root().ordinal()).thenComparing(Preservation::relativePath)).toList(), List.of(), List.of(), List.of());
	}

	public static UpdatePlan plan(Input input) {
		Objects.requireNonNull(input);
		ModpackJsons.ModpackContentFields target = Objects.requireNonNull(input.targetManifest());
		ModpackId.requireValid(target.modpackId);
		GenerationTarget generationTarget = GenerationTarget.fromFlat(target);
		OwnershipLedger ledger = OwnershipLedger.fromFields(target.ownershipLedger);
		if (!target.modpackId.equals(ledger.modpackId())) throw new IllegalArgumentException("Target ledger modpack ID does not match target");
		if (input.installedManifest() != null) GenerationTarget.fromFlat(input.installedManifest());
		if (target.list == null) throw new IllegalArgumentException("Target manifest list is missing");

		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> targetItems = sortedItems(target.list);
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> installedItems = input.installedManifest() == null
				|| input.installedManifest().list == null ? Map.of() : sortedItems(input.installedManifest().list);
		Map<FileKey, FileState> projected = new HashMap<>(input.files());
		Set<FileKey> projectedScope = new HashSet<>(input.files().keySet());
		Map<FileKey, Operation> operations = new HashMap<>();
		EnumSet<RestartReason> restartReasons = EnumSet.noneOf(RestartReason.class);
		List<Preservation> preservations = new ArrayList<>();
		List<BaselineCapture> baselineCaptures = new ArrayList<>();
		List<Conflict> conflicts = new ArrayList<>();
		OwnershipLedger installedLedger = input.installedManifest() == null ? null : OwnershipLedger.fromFields(input.installedManifest().ownershipLedger);

		for (var entry : installedItems.entrySet()) {
			if (targetItems.containsKey(entry.getKey())) continue;
			FileKey modpackKey = new FileKey(Root.PROJECTION, normalize(entry.getKey()));
			delete(operations, projected, modpackKey, null);
			if (input.installedManifest() != null && input.installedManifest().modpackId.equals(target.modpackId)) {
				FileKey overlayKey = new FileKey(Root.OVERLAY, normalize(entry.getKey()));
				if (projected.containsKey(overlayKey)) delete(operations, projected, overlayKey, projected.get(overlayKey).sha1());
			}
			FileKey liveKey = liveKey(entry.getValue());
			FileState live = projected.get(liveKey);
			FileState previousOverlay = input.selection() == null ? null : input.selection().previousEditableOverlays().get(entry.getKey());
			if (previousOverlay != null && previousOverlay.regularFile() && live != null && hashesEqual(live.sha1(), previousOverlay.sha1())) {
				delete(operations, projected, liveKey, previousOverlay.sha1());
				restartReasons.add(RestartReason.REMOVED_NON_MODPACK_FILES);
			}
		}

		if (installedLedger != null) planLedgerCleanup(installedLedger, targetItems.keySet(), projected, operations, preservations, restartReasons);
		else planServerKnownCleanup(ledger, targetItems.keySet(), projected, operations, preservations, restartReasons);
		if (input.installedManifest() != null && !Objects.equals(input.installedManifest().selectedGroups, target.selectedGroups))
			restartReasons.add(RestartReason.CHANGED_GROUP_SELECTION);
		if (input.installedManifest() == null || isSelectionChange(input.selection(), target.modpackId)) restartReasons.add(RestartReason.SELECTED_MODPACK);
		Set<String> forceCopyPaths = new HashSet<>(input.forceCopyServicePaths());

		for (var item : targetItems.values()) {
			String relative = normalize(item.file);
			boolean activeMod = ModpackPathPolicy.isActiveMod(relative, item.type);
			FileKey modpackKey = new FileKey(Root.PROJECTION, relative);
			FileState existing = projected.get(modpackKey);
			boolean installedHashChanged = !hashesEqual(item.sha1, Optional.ofNullable(installedItems.get(relative)).map(old -> old.sha1).orElse(null));
			boolean overwriteEditable = item.editable && item.overwriteEditable && installedHashChanged;
			FileState overlay = item.editable && !overwriteEditable ? input.editableOverlays().get(relative) : null;
			if (overlay != null && overlay.regularFile() && !matches(projected.get(new FileKey(Root.OVERLAY, relative)), overlay.sha1(), overlay.size()))
				install(operations, projected, new FileKey(Root.OVERLAY, relative), overlay.sha1(), overlay.size());
			if (overlay == null && projected.containsKey(new FileKey(Root.OVERLAY, relative)))
				delete(operations, projected, new FileKey(Root.OVERLAY, relative), projected.get(new FileKey(Root.OVERLAY, relative)).sha1());
			if (!matches(existing, item.sha1, parseSize(item.size)))
				install(operations, projected, modpackKey, item.sha1, parseSize(item.size));

			boolean copyToLive = !activeMod || forceCopyPaths.contains(relative) || overlay != null;
			FileKey liveKey = liveKey(item);
			if (copyToLive) {
				FileState live = projected.get(liveKey);
				if (overlay != null && !overlay.regularFile()) {
					if (live != null) delete(operations, projected, liveKey, live.sha1());
				} else {
					String liveHash = overlay == null ? item.sha1 : overlay.sha1();
					long liveSize = overlay == null ? parseSize(item.size) : overlay.size();
					if (!matches(live, liveHash, liveSize)) {
						install(operations, projected, liveKey, liveHash, liveSize);
						if (activeMod) restartReasons.add(RestartReason.CORRECTED_FILE_LOCATIONS);
					}
				}
			}
		}

		List<NestedCopy> generatedCopies = ownedNestedCopies(input.nestedCopies());
		planNestedCopies(input.previousNestedCopies(), generatedCopies, projected, operations, restartReasons);
		conflicts.addAll(planDuplicates(target.modpackId, input.targetMods(), input.standardMods(), forceCopyPaths, installedLedger, projected, operations, restartReasons));

		planBaselineCaptures(input.files(), operations, baselineCaptures);
		List<Operation> ordered = operations.values().stream().sorted(OPERATION_ORDER).toList();
		projectedScope.addAll(operations.keySet());
		List<ProjectedFile> finalState = projectedScope.stream().sorted(FILE_KEY_ORDER).map(key -> {
			FileState state = projected.get(key);
			return state == null || !state.regularFile()
					? new ProjectedFile(key.root(), key.relativePath(), false, null, -1)
					: new ProjectedFile(key.root(), key.relativePath(), true, state.sha1(), state.size());
		}).toList();
		return new UpdatePlan(target.modpackId, generationTarget, ordered, finalState, input.plannedClientConfig(), restartReasons,
				preservations.stream().sorted(Comparator.comparing((Preservation preservation) -> preservation.root().ordinal()).thenComparing(Preservation::relativePath)).toList(),
				baselineCaptures.stream().sorted(Comparator.comparing((BaselineCapture capture) -> capture.root().ordinal()).thenComparing(BaselineCapture::relativePath)).toList(),
				conflicts.stream().sorted(Comparator.comparing(Conflict::conflictId)).toList(), generatedCopies);
	}

	private static void planBaselineCaptures(Map<FileKey, FileState> original, Map<FileKey, Operation> operations,
			List<BaselineCapture> captures) {
		Map<FileKey, BaselineCapture> planned = new HashMap<>();
		for (Operation operation : operations.values()) {
			if ((operation.operation() != OperationType.INSTALL_OBJECT && operation.operation() != OperationType.DELETE)
					|| operation.root() != Root.GAME_DIR)
				continue;
			FileKey key = new FileKey(operation.root(), normalize(operation.relativePath()));
			FileState previous = original.get(key);
			if (previous != null && (!previous.regularFile() || previous.sha1() == null || previous.size() < 0))
				throw new IllegalArgumentException("Cannot capture a safe baseline for live path: " + key.relativePath());
			BaselineCapture capture = previous == null
					? new BaselineCapture(key.root(), key.relativePath(), "", -1, true)
					: new BaselineCapture(key.root(), key.relativePath(), previous.sha1().toLowerCase(Locale.ROOT), previous.size(), false);
			planned.putIfAbsent(key, capture);
		}
		captures.addAll(planned.values());
	}

	private static void planLedgerCleanup(OwnershipLedger ledger, Set<String> targetPaths, Map<FileKey, FileState> projected,
			Map<FileKey, Operation> operations, List<Preservation> preservations, EnumSet<RestartReason> restartReasons) {
		for (OwnershipLedger.Entry entry : ledger.entries().values()) {
			if (targetPaths.contains(entry.logicalPath())) continue;
			Optional<FileKey> candidateKey = managedCleanupKey(entry.logicalPath());
			if (candidateKey.isEmpty()) continue;
			FileKey key = candidateKey.get();
			FileState state = projected.get(key);
			if (state == null || !state.regularFile() || state.sha1() == null) continue;
			OwnershipLedger.Content content = new OwnershipLedger.Content(state.sha1().toLowerCase(Locale.ROOT), state.size());
			if (!entry.historicalHashes().contains(content)) continue;
			preservations.add(new Preservation(key.root(), key.relativePath(), state.sha1().toLowerCase(Locale.ROOT), state.size()));
			delete(operations, projected, key, state.sha1());
			restartReasons.add(RestartReason.APPLIED_SERVER_DELETIONS);
		}
	}

	private static void planServerKnownCleanup(OwnershipLedger ledger, Set<String> targetPaths, Map<FileKey, FileState> projected,
			Map<FileKey, Operation> operations, List<Preservation> preservations, EnumSet<RestartReason> restartReasons) {
		for (OwnershipLedger.Entry entry : ledger.entries().values()) {
			if (entry.currentStatus() != OwnershipLedger.Status.TOMBSTONE || targetPaths.contains(entry.logicalPath())) continue;
			Optional<FileKey> candidateKey = managedCleanupKey(entry.logicalPath());
			if (candidateKey.isEmpty()) continue;
			FileKey key = candidateKey.get();
			FileState state = projected.get(key);
			if (state == null || !state.regularFile() || state.sha1() == null) continue;
			OwnershipLedger.Content content = new OwnershipLedger.Content(state.sha1().toLowerCase(Locale.ROOT), state.size());
			if (!entry.historicalHashes().contains(content)) continue;
			preservations.add(new Preservation(key.root(), key.relativePath(), state.sha1().toLowerCase(Locale.ROOT), state.size(), PreservationProof.SERVER_LEDGER));
			delete(operations, projected, key, state.sha1());
			restartReasons.add(RestartReason.APPLIED_SERVER_DELETIONS);
		}
	}

	public static Optional<FileKey> managedCleanupKey(String logicalPath) {
		final String normalized;
		try {
			normalized = normalize(logicalPath);
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

	private static void planNestedCopies(List<NestedCopy> previousCopies, List<NestedCopy> copies, Map<FileKey, FileState> projected, Map<FileKey, Operation> operations,
			EnumSet<RestartReason> restartReasons) {
		Map<String, NestedCopy> previousByPath = previousCopies.stream().collect(Collectors.toMap(NestedCopy::relativePath, Function.identity(), (first, second) -> {
			throw new IllegalArgumentException("Duplicate previous generated-copy path: " + first.relativePath());
		}, TreeMap::new));
		Set<String> targetPaths = copies.stream().map(NestedCopy::relativePath).collect(Collectors.toSet());
		for (NestedCopy previous : previousCopies.stream().sorted(Comparator.comparing(NestedCopy::relativePath)).toList()) {
			if (targetPaths.contains(previous.relativePath())) continue;
			FileKey key = new FileKey(Root.GAME_DIR, normalize(previous.relativePath()));
			FileState current = projected.get(key);
			if (matches(current, previous.sha1(), previous.size())) {
				delete(operations, projected, key, previous.sha1());
				restartReasons.add(RestartReason.FIXED_NESTED_MODS);
			}
		}
		for (NestedCopy copy : copies) {
			FileKey key = new FileKey(Root.GAME_DIR, normalize(copy.relativePath()));
			FileState current = projected.get(key);
			if (!matches(current, copy.sha1(), copy.size())) {
				NestedCopy previous = previousByPath.get(copy.relativePath());
				if (current != null && (previous == null || !matches(current, previous.sha1(), previous.size()))) {
					continue;
				}
				String expectedExistingHash = previous == null ? null : previous.sha1();
				install(operations, projected, key, copy.sha1(), copy.size(), expectedExistingHash);
				restartReasons.add(RestartReason.FIXED_NESTED_MODS);
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

	private static List<Conflict> planDuplicates(String modpackId, List<ModInfo> targetMods, List<ModInfo> standardMods, Set<String> forceCopyPaths,
			OwnershipLedger installedLedger, Map<FileKey, FileState> projected, Map<FileKey, Operation> operations, EnumSet<RestartReason> restartReasons) {
		List<ModInfo> sortedTarget = targetMods.stream().filter(mod -> projected.containsKey(new FileKey(Root.PROJECTION, normalize(mod.relativePath()))))
				.sorted(Comparator.comparing(ModInfo::relativePath)).toList();
		List<ModInfo> sortedStandard = standardMods.stream().filter(mod -> projected.containsKey(new FileKey(Root.GAME_DIR, normalize(mod.relativePath()))))
				.sorted(Comparator.comparing(ModInfo::relativePath)).toList();
		Map<ModInfo, ModInfo> duplicates = new LinkedHashMap<>();
		for (ModInfo target : sortedTarget) {
			if (forceCopyPaths.contains(normalize(target.relativePath()))) continue;
			sortedStandard.stream().filter(standard -> intersects(target.ids(), standard.ids())).findFirst().ifPresent(standard -> duplicates.put(target, standard));
		}
		Set<ModInfo> keep = new HashSet<>();
		for (ModInfo standard : sortedStandard) if (!duplicates.containsValue(standard)) addDependencies(standard, sortedStandard, keep);
		Set<String> idsToKeep = keep.stream().flatMap(mod -> mod.ids().stream()).collect(Collectors.toSet());
		List<Conflict> conflicts = new ArrayList<>();

		for (var duplicate : duplicates.entrySet()) {
			ModInfo target = duplicate.getKey();
			ModInfo standard = duplicate.getValue();
			FileKey oldKey = new FileKey(Root.GAME_DIR, normalize(standard.relativePath()));
			boolean owned = isOwned(standard, installedLedger);
			boolean keepStandard = target.ids().stream().anyMatch(idsToKeep::contains);
			FileKey targetKey = new FileKey(Root.GAME_DIR, normalize(target.relativePath()));
			boolean targetAlreadyMatches = matches(projected.get(targetKey), target.sha1(), target.size());
			boolean sourceNeedsDisposition = !oldKey.equals(targetKey) || !keepStandard || !targetAlreadyMatches;
			if (sourceNeedsDisposition) conflicts.add(conflict(modpackId, target, standard, owned ? ConflictAction.REMOVE_OWNED : ConflictAction.QUARANTINE));
			if (keepStandard) {
				if (!targetAlreadyMatches) {
					install(operations, projected, targetKey, target.sha1(), target.size(),
							oldKey.equals(targetKey) ? standard.sha1() : null);
					restartReasons.add(RestartReason.REMOVED_DUPLICATE_MODS);
				}
				if (!oldKey.equals(targetKey)) delete(operations, projected, oldKey, standard.sha1());
			} else {
				delete(operations, projected, oldKey, standard.sha1());
				restartReasons.add(RestartReason.REMOVED_DUPLICATE_MODS);
			}
		}
		return conflicts;
	}

	private static boolean isOwned(ModInfo standard, OwnershipLedger ledger) {
		if (ledger == null) return false;
		OwnershipLedger.Entry entry = ledger.entries().get(normalize(standard.relativePath()));
		return entry != null && entry.historicalHashes().contains(new OwnershipLedger.Content(standard.sha1().toLowerCase(Locale.ROOT), standard.size()));
	}

	private static Conflict conflict(String modpackId, ModInfo target, ModInfo standard, ConflictAction action) {
		String sourcePath = normalize(standard.relativePath());
		String targetPath = normalize(target.relativePath());
		String identity = conflictId(target, standard);
		Set<String> ids = new TreeSet<>(target.ids());
		ids.addAll(standard.ids());
		return new Conflict(modpackId, identity, ids, sourcePath, standard.sha1(), standard.size(), targetPath, target.sha1(), target.size(), action);
	}

	private static String conflictId(ModInfo target, ModInfo standard) {
		String value = String.join("\n", normalize(target.relativePath()), target.sha1().toLowerCase(Locale.ROOT), normalize(standard.relativePath()),
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
		String normalized = normalize(item.file);
		if (!ModpackPathPolicy.isValidTypeAndPath(normalized, item.type))
			throw new IllegalArgumentException("Invalid manifest type/path combination: " + item.type + " " + item.file);
		return normalized;
	}

	private static FileKey liveKey(ModpackJsons.ModpackContentFields.ModpackContentItem item) {
		String relative = normalize(item.file);
		return new FileKey(Root.GAME_DIR, relative);
	}

	private static void install(Map<FileKey, Operation> operations, Map<FileKey, FileState> projected, FileKey key, String hash, long size) {
		install(operations, projected, key, hash, size, null);
	}

	private static void install(Map<FileKey, Operation> operations, Map<FileKey, FileState> projected, FileKey key, String hash, long size,
			String expectedExistingHash) {
		operations.put(key, new Operation(key.root(), key.relativePath(), OperationType.INSTALL_OBJECT, hash, size, expectedExistingHash));
		projected.put(key, new FileState(hash, size, true));
	}

	private static void delete(Map<FileKey, Operation> operations, Map<FileKey, FileState> projected, FileKey key, String expectedHash) {
		FileState existing = projected.get(key);
		String safeExpectedHash = expectedHash != null ? expectedHash : existing == null ? null : existing.sha1();
		operations.put(key, new Operation(key.root(), key.relativePath(), OperationType.DELETE, null, -1, safeExpectedHash));
		projected.remove(key);
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

	private static long parseSize(String size) {
		try {
			long parsed = Long.parseLong(size);
			if (parsed < 0) throw new IllegalArgumentException("Negative file size");
			return parsed;
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid file size: " + size, e);
		}
	}

	public static String normalize(String path) {
		return LogicalPath.normalize(path);
	}
}
