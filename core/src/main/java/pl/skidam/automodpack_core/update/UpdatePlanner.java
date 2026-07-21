package pl.skidam.automodpack_core.update;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.update.UpdatePlan.*;

public final class UpdatePlanner {
	private static final Comparator<Operation> OPERATION_ORDER = Comparator.comparing((Operation operation) -> operation.operation().ordinal())
			.thenComparing(operation -> operation.root().ordinal()).thenComparing(Operation::relativePath);
	private static final Comparator<FileKey> FILE_KEY_ORDER = Comparator.comparing((FileKey key) -> key.root().ordinal()).thenComparing(FileKey::relativePath);

	private UpdatePlanner() {}

	public record Input(
			Jsons.ModpackContentFields installedManifest,
			Jsons.ModpackContentFields targetManifest,
			Map<FileKey, FileState> files,
			boolean allowRemoteDeletions,
			Set<String> evaluatedDeletionTimestamps,
			Set<String> forceCopyServicePaths,
			List<ModInfo> targetMods,
			List<ModInfo> standardMods,
			List<NestedCopy> nestedCopies,
			SelectionContext selection,
			Jsons.ClientConfigFieldsV3 plannedClientConfig) {
		public Input {
			files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
			evaluatedDeletionTimestamps = Collections.unmodifiableSet(new LinkedHashSet<>(evaluatedDeletionTimestamps));
			forceCopyServicePaths = Collections.unmodifiableSet(new LinkedHashSet<>(forceCopyServicePaths));
			targetMods = List.copyOf(targetMods);
			standardMods = List.copyOf(standardMods);
			nestedCopies = List.copyOf(nestedCopies);
		}
	}

	public record SelectionContext(String previousModpackId, Jsons.ModpackContentFields previousManifest) {}

	public static UpdatePlan plan(Input input) {
		Objects.requireNonNull(input);
		Jsons.ModpackContentFields target = Objects.requireNonNull(input.targetManifest());
		ModpackId.requireValid(target.modpackId);
		if (target.list == null) throw new IllegalArgumentException("Target manifest list is missing");

		Map<String, Jsons.ModpackContentFields.ModpackContentItem> targetItems = sortedItems(target.list);
		Map<String, Jsons.ModpackContentFields.ModpackContentItem> installedItems = input.installedManifest() == null
				|| input.installedManifest().list == null ? Map.of() : sortedItems(input.installedManifest().list);
		Map<FileKey, FileState> projected = new HashMap<>(input.files());
		Set<FileKey> projectedScope = new HashSet<>(input.files().keySet());
		Map<FileKey, Operation> operations = new HashMap<>();
		EnumSet<RestartReason> restartReasons = EnumSet.noneOf(RestartReason.class);
		Set<String> timestamps = new TreeSet<>();
		List<Warning> warnings = new ArrayList<>();

		for (var entry : installedItems.entrySet()) {
			if (targetItems.containsKey(entry.getKey())) continue;
			FileKey modpackKey = new FileKey(Root.MODPACK_DIR, normalize(entry.getKey()));
			delete(operations, projected, modpackKey, null);
			FileKey liveKey = liveKey(entry.getValue());
			FileState live = projected.get(liveKey);
			if (live != null && hashesEqual(live.sha1(), entry.getValue().sha1)) {
				delete(operations, projected, liveKey, entry.getValue().sha1);
				restartReasons.add(RestartReason.REMOVED_NON_MODPACK_FILES);
			}
		}

		planRemoteDeletions(input, projected, operations, timestamps, restartReasons, warnings);
		if (isSelectionChange(input.selection(), target.modpackId)) restartReasons.add(RestartReason.SELECTED_MODPACK);
		planPreviousEditablePreservation(input.selection(), target.modpackId, projected, operations);

		Set<String> forceCopyPaths = new HashSet<>(input.forceCopyServicePaths());
		for (var item : targetItems.values()) if (item.forceCopy) forceCopyPaths.add(normalize(item.file));

		for (var item : targetItems.values()) {
			String relative = normalize(item.file);
			FileKey modpackKey = new FileKey(Root.MODPACK_DIR, relative);
			FileState existing = projected.get(modpackKey);
			boolean installedHashChanged = !hashesEqual(item.sha1, Optional.ofNullable(installedItems.get(relative)).map(old -> old.sha1).orElse(null));
			boolean preserveEditable = item.editable && existing != null && !(item.overwriteEditable && installedHashChanged);
			if (!preserveEditable && !matches(existing, item.sha1, parseSize(item.size))) {
				install(operations, projected, modpackKey, item.sha1, parseSize(item.size), "mod".equals(item.type));
			}

			boolean copyToLive = !"mod".equals(item.type) || forceCopyPaths.contains(relative);
			FileKey liveKey = liveKey(item);
			if (copyToLive && !preserveEditable) {
				FileState live = projected.get(liveKey);
				if (!matches(live, item.sha1, parseSize(item.size))) {
					install(operations, projected, liveKey, item.sha1, parseSize(item.size), "mod".equals(item.type));
					if ("mod".equals(item.type)) restartReasons.add(RestartReason.CORRECTED_FILE_LOCATIONS);
				}
			}
		}

		planSelectedEditableCopies(input.selection(), target.modpackId, targetItems.values(), projected, operations);
		planNestedCopies(input.nestedCopies(), projected, operations, restartReasons);
		Set<String> standardModsToKeep = planDuplicates(input.targetMods(), input.standardMods(), forceCopyPaths, projected, operations, restartReasons);

		for (var item : targetItems.values()) {
			if (!"mod".equals(item.type)) continue;
			String relative = normalize(item.file);
			if (forceCopyPaths.contains(relative)) continue;
			FileKey standardKey = liveKey(item);
			FileState standard = projected.get(standardKey);
			if (standard != null && hashesEqual(standard.sha1(), item.sha1) && !standardModsToKeep.contains(standardKey.relativePath())) {
				delete(operations, projected, standardKey, item.sha1);
				restartReasons.add(RestartReason.REMOVED_STANDARD_MODS);
			}
		}

		List<Operation> ordered = operations.values().stream().sorted(OPERATION_ORDER).toList();
		projectedScope.addAll(operations.keySet());
		List<ProjectedFile> finalState = projectedScope.stream().sorted(FILE_KEY_ORDER).map(key -> {
			FileState state = projected.get(key);
			return state == null
					? new ProjectedFile(key.root(), key.relativePath(), false, null, -1)
					: new ProjectedFile(key.root(), key.relativePath(), true, state.sha1(), state.size());
		}).toList();
		return new UpdatePlan(target.modpackId, ordered, finalState, input.plannedClientConfig(), timestamps, restartReasons, warnings);
	}

	private static boolean isSelectionChange(SelectionContext selection, String targetModpackId) {
		return selection != null && selection.previousModpackId() != null && !selection.previousModpackId().isBlank()
				&& !selection.previousModpackId().equals(targetModpackId);
	}

	private static void planPreviousEditablePreservation(SelectionContext selection, String targetModpackId, Map<FileKey, FileState> projected,
			Map<FileKey, Operation> operations) {
		if (selection == null || selection.previousModpackId() == null || selection.previousModpackId().isBlank()
				|| selection.previousModpackId().equals(targetModpackId) || selection.previousManifest() == null || selection.previousManifest().list == null)
			return;
		ModpackId.requireValid(selection.previousModpackId());
		for (var item : selection.previousManifest().list.stream().filter(value -> value.editable).sorted(Comparator.comparing(value -> value.file)).toList()) {
			FileKey gameKey = liveKey(item);
			FileState current = projected.get(gameKey);
			if (current == null || !current.regularFile()) continue;
			FileKey oldModpackKey = new FileKey(Root.AUTOMODPACK_DIR,
					"modpacks/" + selection.previousModpackId() + "/" + normalize(item.file));
			install(operations, projected, oldModpackKey, current.sha1(), current.size(), current.mod());
		}
	}

	private static void planSelectedEditableCopies(SelectionContext selection, String targetModpackId,
			Collection<Jsons.ModpackContentFields.ModpackContentItem> targetItems, Map<FileKey, FileState> projected, Map<FileKey, Operation> operations) {
		if (selection == null || selection.previousModpackId() == null || selection.previousModpackId().isBlank()
				|| selection.previousModpackId().equals(targetModpackId))
			return;
		for (var item : targetItems) {
			if (!item.editable || "mod".equals(item.type)) continue;
			FileState selectedCopy = projected.get(new FileKey(Root.MODPACK_DIR, normalize(item.file)));
			if (selectedCopy == null || !selectedCopy.regularFile()) continue;
			install(operations, projected, liveKey(item), selectedCopy.sha1(), selectedCopy.size(), selectedCopy.mod());
		}
	}

	private static void planRemoteDeletions(Input input, Map<FileKey, FileState> projected, Map<FileKey, Operation> operations, Set<String> timestamps,
			EnumSet<RestartReason> restartReasons, List<Warning> warnings) {
		Set<Jsons.ModpackContentFields.FileToDelete> requests = input.targetManifest().nonModpackFilesToDelete == null
				? Set.of()
				: input.targetManifest().nonModpackFilesToDelete;
		Comparator<Jsons.ModpackContentFields.FileToDelete> requestOrder = Comparator.comparing((Jsons.ModpackContentFields.FileToDelete value) -> value.timestamp == null
				? ""
				: value.timestamp).thenComparing(value -> value.file == null ? "" : value.file).thenComparing(value -> value.sha1 == null ? "" : value.sha1);
		for (var request : requests.stream().sorted(requestOrder).toList()) {
			if (request.timestamp == null || input.evaluatedDeletionTimestamps().contains(request.timestamp)) continue;
			String requested = normalize(request.file);
			if (!input.allowRemoteDeletions()) {
				warnings.add(new Warning(WarningType.REMOTE_DELETION_DISABLED, request.timestamp, requested, request.sha1, null, null));
				continue;
			}
			List<FileKey> candidates = projected.keySet().stream().filter(key -> key.root() == Root.GAME_DIR || key.root() == Root.MODS_DIR)
					.filter(key -> logicalGamePath(key).equals(requested) || sameParent(logicalGamePath(key), requested))
					.sorted(Comparator.comparing(UpdatePlanner::logicalGamePath)).toList();
			boolean matched = false;
			List<Warning> mismatches = new ArrayList<>();
			for (FileKey key : candidates) {
				FileState state = projected.get(key);
				if (state != null && state.regularFile() && hashesEqual(state.sha1(), request.sha1)) {
					delete(operations, projected, key, request.sha1);
					matched = true;
					if (state.mod()) restartReasons.add(RestartReason.APPLIED_SERVER_DELETIONS);
				} else if (state != null && state.regularFile()) {
					mismatches.add(new Warning(WarningType.REMOTE_DELETION_HASH_MISMATCH, request.timestamp, requested, request.sha1,
							logicalGamePath(key), state.sha1()));
				}
			}
			if (!matched) {
				if (mismatches.isEmpty()) warnings.add(new Warning(WarningType.REMOTE_DELETION_HASH_MISMATCH, request.timestamp, requested, request.sha1, null, null));
				else warnings.addAll(mismatches);
			}
			timestamps.add(request.timestamp);
		}
	}

	private static void planNestedCopies(List<NestedCopy> copies, Map<FileKey, FileState> projected, Map<FileKey, Operation> operations,
			EnumSet<RestartReason> restartReasons) {
		Set<String> standardIds = new HashSet<>();
		for (NestedCopy copy : copies.stream().sorted(Comparator.comparing(NestedCopy::targetFileName)).toList()) {
			if (copy.ids().stream().anyMatch(standardIds::contains)) continue;
			FileKey key = new FileKey(Root.MODS_DIR, normalize(copy.targetFileName()));
			if (!matches(projected.get(key), copy.sha1(), copy.size())) {
				install(operations, projected, key, copy.sha1(), copy.size(), true);
				restartReasons.add(RestartReason.FIXED_NESTED_MODS);
			}
			standardIds.addAll(copy.ids());
		}
	}

	private static Set<String> planDuplicates(List<ModInfo> targetMods, List<ModInfo> standardMods, Set<String> forceCopyPaths,
			Map<FileKey, FileState> projected, Map<FileKey, Operation> operations, EnumSet<RestartReason> restartReasons) {
		List<ModInfo> sortedTarget = targetMods.stream().filter(mod -> projected.containsKey(new FileKey(Root.MODPACK_DIR, normalize(mod.relativePath()))))
				.sorted(Comparator.comparing(ModInfo::relativePath)).toList();
		List<ModInfo> sortedStandard = standardMods.stream().filter(mod -> projected.containsKey(new FileKey(Root.MODS_DIR, normalize(mod.relativePath()))))
				.sorted(Comparator.comparing(ModInfo::relativePath)).toList();
		Map<ModInfo, ModInfo> duplicates = new LinkedHashMap<>();
		for (ModInfo target : sortedTarget) {
			if (forceCopyPaths.contains(normalize(target.relativePath()))) continue;
			sortedStandard.stream().filter(standard -> intersects(target.ids(), standard.ids())).findFirst().ifPresent(standard -> duplicates.put(target, standard));
		}
		Set<ModInfo> keep = new HashSet<>();
		for (ModInfo standard : sortedStandard) if (!duplicates.containsValue(standard)) addDependencies(standard, sortedStandard, keep);
		Set<String> idsToKeep = keep.stream().flatMap(mod -> mod.ids().stream()).collect(Collectors.toSet());
		Set<String> pathsToKeep = keep.stream().map(mod -> normalize(mod.relativePath())).collect(Collectors.toSet());

		for (var duplicate : duplicates.entrySet()) {
			ModInfo target = duplicate.getKey();
			ModInfo standard = duplicate.getValue();
			FileKey oldKey = new FileKey(Root.MODS_DIR, normalize(standard.relativePath()));
			if (target.ids().stream().anyMatch(idsToKeep::contains)) {
				String targetName = Path.of(normalize(target.relativePath())).getFileName().toString();
				FileKey targetKey = new FileKey(Root.MODS_DIR, targetName);
				pathsToKeep.add(targetName);
				if (!matches(projected.get(targetKey), target.sha1(), target.size())) {
					install(operations, projected, targetKey, target.sha1(), target.size(), true);
					restartReasons.add(RestartReason.REMOVED_DUPLICATE_MODS);
				}
				if (!oldKey.equals(targetKey)) delete(operations, projected, oldKey, standard.sha1());
			} else {
				delete(operations, projected, oldKey, standard.sha1());
				restartReasons.add(RestartReason.REMOVED_DUPLICATE_MODS);
			}
		}
		return pathsToKeep;
	}

	private static void addDependencies(ModInfo mod, List<ModInfo> all, Set<ModInfo> result) {
		if (!result.add(mod)) return;
		for (String dependency : mod.dependencies()) for (ModInfo candidate : all) {
			if (candidate.ids().stream().anyMatch(id -> id.equalsIgnoreCase(dependency))) addDependencies(candidate, all, result);
		}
	}

	private static Map<String, Jsons.ModpackContentFields.ModpackContentItem> sortedItems(Set<Jsons.ModpackContentFields.ModpackContentItem> items) {
		return items.stream().sorted(Comparator.comparing(item -> normalize(item.file))).collect(Collectors.toMap(item -> normalize(item.file), Function.identity(),
				(first, second) -> {
					throw new IllegalArgumentException("Duplicate normalized manifest path: " + first.file);
				}, LinkedHashMap::new));
	}

	private static FileKey liveKey(Jsons.ModpackContentFields.ModpackContentItem item) {
		String relative = normalize(item.file);
		if ("mod".equals(item.type)) return new FileKey(Root.MODS_DIR, Path.of(relative).getFileName().toString());
		return new FileKey(Root.GAME_DIR, relative);
	}

	private static void install(Map<FileKey, Operation> operations, Map<FileKey, FileState> projected, FileKey key, String hash, long size, boolean mod) {
		operations.put(key, new Operation(key.root(), key.relativePath(), OperationType.INSTALL_OBJECT, hash, size, null));
		projected.put(key, new FileState(hash, size, true, mod));
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

	private static String logicalGamePath(FileKey key) {
		return key.root() == Root.MODS_DIR ? "mods/" + key.relativePath() : key.relativePath();
	}

	private static boolean sameParent(String first, String second) {
		Path firstParent = Path.of(first).getParent();
		Path secondParent = Path.of(second).getParent();
		return firstParent != null && firstParent.equals(secondParent);
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
		if (path == null || path.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid relative path");
		String normalized = path.replace('\\', '/');
		while (normalized.startsWith("/")) normalized = normalized.substring(1);
		Path value = Path.of(normalized).normalize();
		if (value.isAbsolute() || normalized.isBlank() || value.startsWith("..")) throw new IllegalArgumentException("Unsafe relative path: " + path);
		return value.toString().replace('\\', '/');
	}
}
