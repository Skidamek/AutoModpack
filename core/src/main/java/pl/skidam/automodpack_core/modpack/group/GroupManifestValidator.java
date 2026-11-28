package pl.skidam.automodpack_core.modpack.group;

import static pl.skidam.automodpack_core.storage.StoragePaths.MODPACK_CONTENT_FILE;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.OsPaths;
import pl.skidam.automodpack_core.utils.PathFold;

public final class GroupManifestValidator {
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
	private static final Pattern ILLEGAL_WINDOWS_COMPONENT = Pattern.compile(".*[<>:\"|?*\\\\\\p{Cntrl}].*");
	private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cntrl}");
	private static final int CATEGORY_NAME_MAX = 64;

	private GroupManifestValidator() {}

	public static GroupManifest validate(ModpackJsons.CompleteModpackContentFields fields) {
		return validate(fields, false);
	}

	/**
	 * A manifest about to be published must keep apart every two names some case-insensitive filesystem may join; one
	 * already published keeps loading under the narrower rule it was published under.
	 */
	public static GroupManifest validateForPublish(ModpackJsons.CompleteModpackContentFields fields) {
		return validate(fields, true);
	}

	private static GroupManifest validate(ModpackJsons.CompleteModpackContentFields fields, boolean publishing) {
		List<String> errors = new ArrayList<>();
		if (fields == null) throw new GroupValidationException(List.of("Complete modpack catalogue is missing"));
		if (!ModpackId.isValid(fields.modpackId)) errors.add("Invalid modpack ID");

		Map<String, GroupManifest.Category> categories = new LinkedHashMap<>();
		Map<String, String> seenCategoryLabels = new LinkedHashMap<>();
		if (fields.categories != null) {
			for (var categoryEntry : fields.categories.entrySet()) {
				String catId = categoryEntry.getKey();
				if (!isValidIdentifier(catId)) {
					errors.add("Invalid category ID: " + catId);
					continue;
				}
				ModpackJsons.CompleteModpackContentFields.CategoryFields category = categoryEntry.getValue();
				if (category == null) {
					errors.add("Category '" + catId + "' is missing its declaration");
					continue;
				}
				String name = validateCategoryDisplayName(catId, category.name, seenCategoryLabels, errors);
				String description = value(category.description);
				if (CONTROL_CHARACTER.matcher(description).find()) errors.add("Category '" + catId + "' description contains control characters");
				if (category.pick == null) {
					errors.add("Category '" + catId + "' is missing its pick mode");
					continue;
				}
				categories.put(catId, new GroupManifest.Category(name, description, category.pick));
			}
		}

		if (fields.groups == null || fields.groups.isEmpty()) errors.add("Group catalogue is empty");
		Map<String, GroupManifest.Group> groups = new LinkedHashMap<>();
		Set<String> populatedCategories = new HashSet<>();
		if (fields.groups != null) {
			for (var entry : fields.groups.entrySet()) {
				String id = entry.getKey();
				if (!isValidIdentifier(id)) {
					errors.add("Invalid group ID: " + id);
					continue;
				}
				if (groups.containsKey(id)) {
					errors.add("Group '" + id + "' is declared more than once");
					continue;
				}
				ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = entry.getValue();
				if (group == null) {
					errors.add("Group '" + id + "' is missing its declaration");
					continue;
				}
				if (group.selection == null) {
					errors.add("Group '" + id + "' is missing its selection mode");
					continue;
				}
				String name = value(group.name);
				if (CONTROL_CHARACTER.matcher(name).find()) errors.add("Group '" + id + "' name contains control characters");
				String description = value(group.description);
				if (CONTROL_CHARACTER.matcher(description).find()) errors.add("Group '" + id + "' description contains control characters");

				String category = value(group.category);
				if (!category.isEmpty()) {
					if (!categories.containsKey(category)) {
						errors.add("Group '" + id + "' references unknown category '" + category + "'");
					} else {
						populatedCategories.add(category);
						if (categories.get(category).pick().isExclusive() && !group.selection.choosable())
							errors.add("Group '" + id + "' cannot use selection " + group.selection + " in exclusive category '" + category + "'");
					}
				}

				Set<String> requires = validateIds("Group '" + id + "' requires", group.requires, errors);
				Set<String> conflicts = validateIds("Group '" + id + "' conflicts with", group.conflicts, errors);
				Set<ClientPlatform> platforms = validatePlatforms(id, group.compatiblePlatforms, errors);
				Map<String, GroupManifest.GroupFile> files = validateFiles(id, group.files, errors);
				groups.put(id, new GroupManifest.Group(name, description, category, group.selection, requires, platforms, files, conflicts));
			}
		}

		for (String catId : categories.keySet()) {
			if (!populatedCategories.contains(catId)) errors.add("Category '" + catId + "' has no groups");
		}

		validateReferences(groups, errors);
		validateCycles(groups, errors);

		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
		GroupManifest manifest = new GroupManifest(fields.modpackId, value(fields.modpackName), value(fields.automodpackVersion), value(fields.loader),
				value(fields.loaderVersion), value(fields.mcVersion), categories, groups);
		PairMemo pairs = new PairMemo(manifest);
		if (publishing)
			for (var group : manifest.groups().entrySet())
				for (String path : group.getValue().files().keySet())
					if (ModpackPathPolicy.isPlayerRuntimePath(path)) errors.add("Group '" + group.getKey() + "' file '" + path + "' is in a folder the game keeps for the player");
		validatePlatformPaths(manifest, publishing, errors, pairs);
		validateDefaultAndIndividualSelections(manifest, errors);
		validateObjectSizes(manifest, errors);
		validateOverlaps(manifest, errors, pairs);
		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
		return manifest;
	}

	private static Map<String, GroupManifest.GroupFile> validateFiles(String groupId,
			Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> input, List<String> errors) {
		Map<String, GroupManifest.GroupFile> files = new LinkedHashMap<>();
		if (input == null) {
			errors.add("Group '" + groupId + "' files are missing");
			return files;
		}
		for (var entry : input.entrySet()) {
			String path = entry.getKey();
			if (!isCanonicalPublishedPath(path, groupId, errors)) continue;
			ModpackJsons.CompleteModpackContentFields.GroupFileFields file = entry.getValue();
			if (file == null) {
				errors.add("Group '" + groupId + "' file '" + path + "' is missing metadata");
				continue;
			}
			long size = -1;
			try {
				size = Long.parseLong(file.size);
				if (size < 0) throw new NumberFormatException("negative");
			} catch (RuntimeException e) {
				errors.add("Group '" + groupId + "' file '" + path + "' has invalid size");
			}
			if (!ModpackPathPolicy.isValidCanonicalPublishedPath(path)) errors.add("Group '" + groupId + "' file '" + path + "' has an invalid path");
			if (!HashUtils.isSha1(file.sha1)) errors.add("Group '" + groupId + "' file '" + path + "' has invalid SHA-1");
			files.put(path, new GroupManifest.GroupFile(size, file.editable, value(file.sha1).toLowerCase(Locale.ROOT), file.murmur));
		}
		return files;
	}

	private static boolean isCanonicalPublishedPath(String path, String groupId, List<String> errors) {
		try {
			LogicalPath.requireCanonical(path);
		} catch (IllegalArgumentException e) {
			errors.add("Group '" + groupId + "': " + e.getMessage());
			return false;
		}
		if (path.equalsIgnoreCase(MODPACK_CONTENT_FILE.toString())) {
			errors.add("Group '" + groupId + "' reserves AutoModpack metadata path: " + path);
			return false;
		}
		return true;
	}

	private static void validatePlatformPaths(GroupManifest manifest, boolean publishing, List<String> errors, PairMemo pairs) {
		for (ClientPlatform platform : coveredPlatforms(manifest)) {
			Map<String, List<PathOwner>> aliases = new LinkedHashMap<>();
			Map<String, List<PathOwner>> modBasenameAliases = new LinkedHashMap<>();
			for (var groupEntry : manifest.groups().entrySet()) {
				String groupId = groupEntry.getKey();
				GroupManifest.Group group = groupEntry.getValue();
				if (!group.supports(platform)) continue;
				for (String path : group.files().keySet()) {
					if (ClientPlatform.WINDOWS.equals(platform)) validateWindowsPath(groupId, path, errors);
					aliases.computeIfAbsent(platformPathKey(path, platform, publishing), ignored -> new ArrayList<>()).add(new PathOwner(groupId, path));
					if (ModpackPathPolicy.isActiveCanonicalMod(path))
						modBasenameAliases.computeIfAbsent(platformPathKey(path.substring(path.lastIndexOf('/') + 1), platform, publishing), ignored -> new ArrayList<>())
								.add(new PathOwner(groupId, path));
				}
			}
			validateAliasOwners(platform, aliases, false, errors, pairs);
			validateAncestorOwners(platform, aliases, errors, pairs);
			validateAliasOwners(platform, modBasenameAliases, true, errors, pairs);
		}
	}

	/**
	 * Ancestor-of is exactly prefix-of: every owner's platform key is normalized once while building the alias
	 * map, so the conflicts come out of strict '/'-boundary prefix lookups instead of a pairwise scan over every
	 * file owner. Equal keys are aliases, never ancestor pairs.
	 */
	private static void validateAncestorOwners(ClientPlatform platform, Map<String, List<PathOwner>> aliases, List<String> errors, PairMemo pairs) {
		for (var entry : aliases.entrySet()) for (PathOwner descendant : entry.getValue()) for (String ancestorKey : ancestorKeys(entry.getKey())) {
			List<PathOwner> ancestors = aliases.get(ancestorKey);
			if (ancestors == null) continue;
			for (PathOwner ancestor : ancestors) reportAncestor(platform, pairs, ancestor, descendant, errors);
		}
	}

	/** The strict prefixes of {@code key} that end at a '/' boundary, shortest first: exactly the keys an ancestor-of relation can name. */
	private static List<String> ancestorKeys(String key) {
		List<String> ancestors = new ArrayList<>();
		for (int cut = key.indexOf('/'); cut >= 0; cut = key.indexOf('/', cut + 1)) ancestors.add(key.substring(0, cut));
		return ancestors;
	}

	private static void reportAncestor(ClientPlatform platform, PairMemo pairs, PathOwner ancestor, PathOwner descendant, List<String> errors) {
		if (!ancestor.groupId().equals(descendant.groupId()) && !pairs.coSelectable(ancestor.groupId(), descendant.groupId())) return;
		String ownerDescription = ancestor.groupId().equals(descendant.groupId())
				? "group '" + ancestor.groupId() + "'"
				: "co-selectable groups '" + ancestor.groupId() + "' and '" + descendant.groupId() + "'";
		errors.add(platform.id() + ": file path '" + ancestor.path() + "' cannot be an ancestor of '" + descendant.path() + "' in " + ownerDescription);
	}

	private static void validateAliasOwners(ClientPlatform platform, Map<String, List<PathOwner>> aliases, boolean modBasenames,
			List<String> errors, PairMemo pairs) {
		String prefix = modBasenames ? "mod files" : "paths";
		String suffix = modBasenames ? " share a basename in the live mods directory" : " differ only in letter case or Unicode form, so case-insensitive filesystems would make them one file";
		for (List<PathOwner> owners : aliases.values()) {
			for (int i = 0; i < owners.size(); i++) for (int j = i + 1; j < owners.size(); j++) {
				PathOwner first = owners.get(i);
				PathOwner second = owners.get(j);
				if (first.path().equals(second.path())) continue;
				if (first.groupId().equals(second.groupId()) || pairs.coSelectable(first.groupId(), second.groupId())) {
					errors.add(platform.id() + ": " + prefix + " '" + first.path() + "' (group '" + first.groupId() + "') and '" + second.path()
							+ "' (group '" + second.groupId() + "')" + suffix);
				}
			}
		}
	}

	private static String platformPathKey(String path, ClientPlatform platform, boolean publishing) {
		if (!ClientPlatform.WINDOWS.equals(platform) && !ClientPlatform.MACOS.equals(platform)) return path;
		return publishing ? PathFold.key(path) : Normalizer.normalize(path, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
	}

	private static void validateWindowsPath(String groupId, String path, List<String> errors) {
		for (String component : path.split("/")) {
			String trimmed = component.stripTrailing();
			if (trimmed.isEmpty() || !trimmed.equals(component) || component.endsWith(".") || ILLEGAL_WINDOWS_COMPONENT.matcher(component).matches())
				errors.add("windows: group '" + groupId + "' has illegal path component in '" + path + "'");
			if (OsPaths.isReservedWindowsDeviceName(trimmed)) errors.add("windows: group '" + groupId + "' uses reserved device name in '" + path + "'");
		}
	}

	private record PathOwner(String groupId, String path) {}

	private static void validateReferences(Map<String, GroupManifest.Group> groups, List<String> errors) {
		for (var entry : groups.entrySet()) {
			String id = entry.getKey();
			GroupManifest.Group group = entry.getValue();
			if (group.requires().contains(id)) errors.add("Group '" + id + "' cannot require itself");
			for (String dependency : group.requires()) if (!groups.containsKey(dependency)) errors.add("Group '" + id + "' requires missing group '" + dependency + "'");
			for (String conflict : group.conflicts()) {
				if (conflict.equals(id)) errors.add("Group '" + id + "' cannot conflict with itself");
				else if (!groups.containsKey(conflict)) errors.add("Group '" + id + "' conflicts with missing group '" + conflict + "'");
			}
		}
	}

	private static void validateCycles(Map<String, GroupManifest.Group> groups, List<String> errors) {
		Set<String> visited = new HashSet<>();
		Set<String> active = new LinkedHashSet<>();
		for (String id : groups.keySet()) detectCycle(id, groups, visited, active, errors);
	}

	private static void detectCycle(String id, Map<String, GroupManifest.Group> groups, Set<String> visited, Set<String> active, List<String> errors) {
		if (active.contains(id)) {
			errors.add("Group dependency cycle: " + String.join(" -> ", active) + " -> " + id);
			return;
		}
		if (!visited.add(id)) return;
		active.add(id);
		GroupManifest.Group group = groups.get(id);
		if (group != null) for (String dependency : group.requires()) detectCycle(dependency, groups, visited, active, errors);
		active.remove(id);
	}

	private static void validateDefaultAndIndividualSelections(GroupManifest manifest, List<String> errors) {
		for (ClientPlatform platform : coveredPlatforms(manifest)) {
			try {
				GroupSelectionResolver.resolveDefault(manifest, platform);
			} catch (SelectionResolutionException e) {
				for (String error : e.errors()) errors.add(platform.id() + ": " + error);
			}
			for (var entry : manifest.groups().entrySet()) {
				GroupManifest.Group group = entry.getValue();
				if (!group.supports(platform) || !group.selection().choosable()) continue;
				try {
					ResolvedSelection selection = GroupSelectionResolver.resolve(manifest, intentActivating(manifest, Set.of(entry.getKey())), platform);
					if (!selection.selectedGroups().contains(entry.getKey()) && !isOptionalUnavailable(manifest, entry.getKey(), selection))
						errors.add(platform.id() + ": Group '" + entry.getKey() + "' cannot be selected on this platform");
				} catch (SelectionResolutionException e) {
					if (!isOptionalUnavailable(manifest, entry.getKey(), e.resolution())) for (String error : e.errors()) errors.add(platform.id() + ": " + error);
				}
			}
		}
		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
	}

	private static SelectionIntent intentActivating(GroupManifest manifest, Set<String> targets) {
		Set<String> enabled = new LinkedHashSet<>(targets);
		ArrayDeque<String> queue = new ArrayDeque<>(targets);
		Set<String> seen = new HashSet<>(targets);
		while (!queue.isEmpty()) {
			String id = queue.removeFirst();
			GroupManifest.Group group = manifest.groups().get(id);
			if (group == null) continue;
			for (String depId : group.requires()) {
				if (!seen.add(depId)) continue;
				GroupManifest.Category depCat = manifest.categoryOf(depId);
				if (depCat != null && depCat.pick().isExclusive()) {
					GroupManifest.Group depGroup = manifest.groups().get(depId);
					if (depGroup != null && depGroup.selection().choosable()) enabled.add(depId);
				}
				queue.addLast(depId);
			}
		}
		Set<String> disabled = new LinkedHashSet<>();
		ArrayDeque<String> toDisable = new ArrayDeque<>();
		for (String activated : seen) {
			for (String conflicting : manifest.conflictsOf(activated)) {
				if (seen.contains(conflicting)) continue;
				if (disabled.add(conflicting)) toDisable.addLast(conflicting);
			}
		}
		while (!toDisable.isEmpty()) {
			String blocked = toDisable.removeFirst();
			for (var entry : manifest.groups().entrySet()) {
				String candidate = entry.getKey();
				if (seen.contains(candidate) || !entry.getValue().requires().contains(blocked)) continue;
				if (disabled.add(candidate)) toDisable.addLast(candidate);
			}
		}
		Set<String> intentDisabled = new LinkedHashSet<>();
		for (String id : disabled) {
			GroupManifest.Group other = manifest.groups().get(id);
			if (other == null) continue;
			GroupManifest.Category otherCat = manifest.categoryOf(id);
			if (other.selection() == GroupSelectionMode.RECOMMENDED || (otherCat != null && otherCat.pick() == CategoryPickMode.EXACTLY_ONE)) intentDisabled.add(id);
		}
		return new SelectionIntent(enabled, intentDisabled);
	}

	private static boolean isOptionalUnavailable(GroupManifest manifest, String groupId, ResolvedSelection selection) {
		GroupManifest.Group group = manifest.groups().get(groupId);
		if (group == null || group.alwaysInstalled()) return false;
		GroupResolution resolution = selection.resolution(groupId);
		return resolution != null && (resolution.status() == GroupResolution.Status.BLOCKED || resolution.status() == GroupResolution.Status.UNAVAILABLE);
	}

	private static void validateObjectSizes(GroupManifest manifest, List<String> errors) {
		Map<String, Long> sizesByHash = new LinkedHashMap<>();
		for (var groupEntry : manifest.groups().entrySet()) for (var fileEntry : groupEntry.getValue().files().entrySet()) {
			GroupManifest.GroupFile file = fileEntry.getValue();
			Long previous = sizesByHash.putIfAbsent(file.sha1(), file.size());
			if (previous != null && previous.longValue() != file.size())
				errors.add("SHA-1 '" + file.sha1() + "' has inconsistent advertised sizes: " + previous + " and " + file.size());
		}
	}

	private static void validateOverlaps(GroupManifest manifest, List<String> errors, PairMemo pairs) {
		Map<String, List<Map.Entry<String, GroupManifest.GroupFile>>> byPath = new LinkedHashMap<>();
		for (var groupEntry : manifest.groups().entrySet())
			for (var fileEntry : groupEntry.getValue().files().entrySet())
				byPath.computeIfAbsent(fileEntry.getKey(), ignored -> new ArrayList<>()).add(Map.entry(groupEntry.getKey(), fileEntry.getValue()));

		for (var pathEntry : byPath.entrySet()) {
			List<Map.Entry<String, GroupManifest.GroupFile>> owners = pathEntry.getValue();
			for (int i = 0; i < owners.size(); i++) for (int j = i + 1; j < owners.size(); j++) {
				var first = owners.get(i);
				var second = owners.get(j);
				if (first.getValue().sameEffectiveState(second.getValue())) continue;
				if (pairs.coSelectable(first.getKey(), second.getKey()))
					errors.add("Path '" + pathEntry.getKey() + "' differs between co-selectable groups '"
							+ first.getKey() + "' and '" + second.getKey() + "'");
			}
		}
	}

	/**
	 * Per-validation memo for the pair check. The resolver run per pair per platform is the validator's only
	 * super-linear cost, and the same pair is asked repeatedly across the alias, ancestor, and overlap passes;
	 * one memo per {@code validate} keeps a hostile catalogue from multiplying resolver runs.
	 */
	private static final class PairMemo {
		private final GroupManifest manifest;
		private final Map<String, Set<String>> activatorsByGroup;
		private final Map<String, Boolean> cache = new HashMap<>();

		PairMemo(GroupManifest manifest) {
			this.manifest = manifest;
			this.activatorsByGroup = computeActivators(manifest);
		}

		boolean coSelectable(String first, String second) {
			String key = first.compareTo(second) < 0 ? first + "\n" + second : second + "\n" + first;
			return cache.computeIfAbsent(key, ignored -> computeCoSelectable(first, second));
		}

		private boolean computeCoSelectable(String first, String second) {
			Set<String> firstTriggers = activatorsByGroup.getOrDefault(first, Set.of());
			Set<String> secondTriggers = activatorsByGroup.getOrDefault(second, Set.of());
			if (firstTriggers.isEmpty() || secondTriggers.isEmpty()) return false;
			for (ClientPlatform platform : coveredPlatforms(manifest)) {
				GroupManifest.Group firstGroup = manifest.groups().get(first);
				GroupManifest.Group secondGroup = manifest.groups().get(second);
				if (!firstGroup.supports(platform) || !secondGroup.supports(platform)) continue;
				for (String t1 : firstTriggers) {
					if (!manifest.groups().get(t1).supports(platform)) continue;
					for (String t2 : secondTriggers) {
						if (!manifest.groups().get(t2).supports(platform)) continue;
						try {
							Set<String> requested = t1.equals(t2) ? Set.of(t1) : Set.of(t1, t2);
							ResolvedSelection selection = GroupSelectionResolver.resolve(manifest, intentActivating(manifest, requested), platform);
							if (selection.selectedGroups().contains(first) && selection.selectedGroups().contains(second)) return true;
						} catch (SelectionResolutionException ignored) {
						}
					}
				}
			}
			return false;
		}

		private static Map<String, Set<String>> computeActivators(GroupManifest manifest) {
			Map<String, Set<String>> activators = new LinkedHashMap<>();
			for (String id : manifest.groups().keySet()) activators.put(id, new LinkedHashSet<>());
			for (var entry : manifest.groups().entrySet()) {
				String root = entry.getKey();
				if (entry.getValue().selection().automatic()) continue;
				activators.get(root).add(root);
				ArrayDeque<String> queue = new ArrayDeque<>(entry.getValue().requires());
				Set<String> seen = new HashSet<>();
				while (!queue.isEmpty()) {
					String dep = queue.removeFirst();
					if (!seen.add(dep)) continue;
					GroupManifest.Group depGroup = manifest.groups().get(dep);
					if (depGroup == null) continue;
					if (depGroup.selection().automatic()) activators.get(dep).add(root);
					queue.addAll(depGroup.requires());
				}
			}
			return activators;
		}
	}

	private static Set<ClientPlatform> validatePlatforms(String groupId, Set<String> input, List<String> errors) {
		if (input == null || input.isEmpty()) return Set.of();
		Set<ClientPlatform> platforms = new TreeSet<>();
		for (String value : input) {
			try {
				platforms.add(ClientPlatform.parse(value));
			} catch (IllegalArgumentException e) {
				errors.add("Group '" + groupId + "' has " + e.getMessage());
			}
		}
		return platforms;
	}

	/** Every platform any rule may match on: the detectable built-ins plus the names the manifest itself declares. */
	private static Set<ClientPlatform> coveredPlatforms(GroupManifest manifest) {
		Set<ClientPlatform> platforms = new TreeSet<>(ClientPlatform.builtIns());
		platforms.addAll(manifest.declaredPlatforms());
		return platforms;
	}

	private static Set<String> validateIds(String description, Set<String> input, List<String> errors) {
		if (input == null) {
			errors.add(description + " is missing");
			return Set.of();
		}
		Set<String> ids = new LinkedHashSet<>();
		for (String id : input) {
			validateId(description, id, errors);
			if (id != null) ids.add(id);
		}
		return ids;
	}

	public static String requireIdentifier(String id) {
		if (!isValidIdentifier(id)) throw new IllegalArgumentException("Invalid identifier: " + id);
		return id;
	}

	public static boolean isValidIdentifier(String id) {
		return id != null && ID.matcher(id).matches() && !OsPaths.isReservedWindowsDeviceName(id);
	}

	private static void validateId(String description, String id, List<String> errors) {
		try {
			requireIdentifier(id);
		} catch (IllegalArgumentException e) {
			errors.add("Invalid " + description + " ID: " + id);
		}
	}

	private static String validateCategoryDisplayName(String catId, String rawName, Map<String, String> seenLabels, List<String> errors) {
		String name = value(rawName);
		if (CONTROL_CHARACTER.matcher(name).find()) errors.add("Category '" + catId + "' name contains control characters");
		if (!name.isEmpty()) {
			if (!name.equals(name.strip())) errors.add("Category '" + catId + "' name has leading or trailing whitespace");
			if (name.strip().isEmpty()) errors.add("Category '" + catId + "' name is blank");
			if (name.length() > CATEGORY_NAME_MAX) errors.add("Category '" + catId + "' name is longer than " + CATEGORY_NAME_MAX + " characters");
		}
		String effectiveLabel = name.isEmpty() ? catId : name;
		String first = seenLabels.putIfAbsent(effectiveLabel.toLowerCase(Locale.ROOT), catId);
		if (first != null) errors.add("Category '" + catId + "' label '" + effectiveLabel + "' duplicates category '" + first + "'");
		return name;
	}

	private static String value(String value) {
		return value == null ? "" : value;
	}
}
