package pl.skidam.automodpack_core.modpack.group;

import static pl.skidam.automodpack_core.storage.StoragePaths.MODPACK_CONTENT_FILE;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.utils.HashUtils;

public final class GroupManifestValidator {
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
	private static final Pattern RESOURCE_NAMESPACE = Pattern.compile("[a-z0-9._-]+");
	private static final Pattern RESOURCE_PATH = Pattern.compile("[a-z0-9/._-]+");

	private GroupManifestValidator() {}

	public static GroupManifest validate(ModpackJsons.CompleteModpackContentFields fields) {
		List<String> errors = new ArrayList<>();
		if (fields == null) throw new GroupValidationException(List.of("Complete modpack catalogue is missing"));
		if (!ModpackId.isValid(fields.modpackId)) errors.add("Invalid modpack ID");
		if (fields.groups == null || fields.groups.isEmpty()) errors.add("Group catalogue is empty");
		Map<String, GroupManifest.Group> groups = new TreeMap<>();
		if (fields.groups != null) for (var entry : fields.groups.entrySet()) {
			String id = entry.getKey();
			ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = entry.getValue();
			if (!isValidIdentifier(id)) {
				errors.add("Invalid group ID: " + id);
				continue;
			}
			if (group == null) {
				errors.add("Group '" + id + "' is missing its declaration");
				continue;
			}
			Set<String> breaksWith = validateIds("Group '" + id + "' breaksWith", group.breaksWith, errors);
			Set<String> requires = validateIds("Group '" + id + "' requires", group.requires, errors);
			String category = validateCategory(id, group.category, errors);
			String icon = validateIcon(id, group.icon, errors);
			Set<ClientPlatform> platforms = validatePlatforms(id, group.compatiblePlatforms, errors);
			Map<String, GroupManifest.GroupFile> files = validateFiles(id, group.files, errors);
			groups.put(id, new GroupManifest.Group(group.displayName, group.description, category, icon, group.required, group.defaultSelected,
					new TreeSet<>(breaksWith), new TreeSet<>(requires), platforms, new TreeMap<>(files)));
		}

		validateReferences(groups, errors);
		validateCycles(groups, errors);

		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
		GroupManifest manifest = new GroupManifest(fields.modpackId, value(fields.modpackName), value(fields.automodpackVersion), value(fields.loader),
				value(fields.loaderVersion), value(fields.mcVersion), new TreeMap<>(groups));
		validatePlatformPaths(manifest, errors);
		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
		validateDefaultAndIndividualSelections(manifest);
		validateObjectSizes(manifest);
		validateOverlaps(manifest);
		return manifest;
	}

	private static Map<String, GroupManifest.GroupFile> validateFiles(String groupId,
			Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> input, List<String> errors) {
		Map<String, GroupManifest.GroupFile> files = new TreeMap<>();
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
			if (file.type == null || !ModpackContentType.ALL.contains(file.type)) errors.add("Group '" + groupId + "' file '" + path + "' has invalid type");
			else
				if (!ModpackPathPolicy.isValidTypeAndPath(path, file.type))
					errors.add("Group '" + groupId + "' file '" + path + "' has an invalid type/path combination: " + file.type);
			if (!HashUtils.isSha1(file.sha1)) errors.add("Group '" + groupId + "' file '" + path + "' has invalid SHA-1");
			files.put(path, new GroupManifest.GroupFile(size, file.type, file.editable,
					value(file.sha1).toLowerCase(Locale.ROOT), file.murmur));
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

	private static void validatePlatformPaths(GroupManifest manifest, List<String> errors) {
		for (ClientPlatform platform : ClientPlatform.values()) {
			Map<String, List<PathOwner>> aliases = new TreeMap<>();
			Map<String, List<PathOwner>> modBasenameAliases = new TreeMap<>();
			for (var groupEntry : manifest.groups().entrySet()) {
				String groupId = groupEntry.getKey();
				GroupManifest.Group group = groupEntry.getValue();
				if (!group.supports(platform)) continue;
				for (String path : group.files().keySet()) {
					if (platform == ClientPlatform.WINDOWS) validateWindowsPath(groupId, path, errors);
					aliases.computeIfAbsent(platformPathKey(path, platform), ignored -> new ArrayList<>()).add(new PathOwner(groupId, path));
					if (ModpackPathPolicy.isActiveMod(path, group.files().get(path).type()))
						modBasenameAliases.computeIfAbsent(platformPathKey(path.substring(path.lastIndexOf('/') + 1), platform), ignored -> new ArrayList<>())
								.add(new PathOwner(groupId, path));
				}
			}
			validateAliasOwners(platform, manifest, aliases, false, errors);
			validateAncestorOwners(platform, manifest, aliases, errors);
			validateAliasOwners(platform, manifest, modBasenameAliases, true, errors);
		}
	}

	private static void validateAncestorOwners(ClientPlatform platform, GroupManifest manifest, Map<String, List<PathOwner>> aliases, List<String> errors) {
		List<PathOwner> owners = aliases.values().stream().flatMap(Collection::stream).toList();
		for (int i = 0; i < owners.size(); i++) for (int j = i + 1; j < owners.size(); j++) {
			PathPair pair = ancestorPair(owners.get(i), owners.get(j), platform);
			if (pair == null) continue;
			if (pair.ancestor().groupId().equals(pair.descendant().groupId()) || coSelectable(manifest, pair.ancestor().groupId(), pair.descendant().groupId())) {
				String ownerDescription = pair.ancestor().groupId().equals(pair.descendant().groupId())
						? "group '" + pair.ancestor().groupId() + "'"
						: "co-selectable groups '" + pair.ancestor().groupId() + "' and '" + pair.descendant().groupId() + "'";
				errors.add(platform.id() + ": file path '" + pair.ancestor().path() + "' cannot be an ancestor of '" + pair.descendant().path() + "' in "
						+ ownerDescription);
			}
		}
	}

	private static PathPair ancestorPair(PathOwner first, PathOwner second, ClientPlatform platform) {
		String firstKey = platformPathKey(first.path(), platform);
		String secondKey = platformPathKey(second.path(), platform);
		if (secondKey.startsWith(firstKey + "/")) return new PathPair(first, second);
		if (firstKey.startsWith(secondKey + "/")) return new PathPair(second, first);
		return null;
	}

	private static void validateAliasOwners(ClientPlatform platform, GroupManifest manifest, Map<String, List<PathOwner>> aliases, boolean modBasenames,
			List<String> errors) {
		String prefix = modBasenames ? "mod files" : "paths";
		String suffix = modBasenames ? " share a basename in the live mods directory" : " alias on this platform";
		for (List<PathOwner> owners : aliases.values()) {
			for (int i = 0; i < owners.size(); i++) for (int j = i + 1; j < owners.size(); j++) {
				PathOwner first = owners.get(i);
				PathOwner second = owners.get(j);
				if (first.path().equals(second.path())) continue;
				if (first.groupId().equals(second.groupId()) || coSelectable(manifest, first.groupId(), second.groupId())) {
					errors.add(platform.id() + ": " + prefix + " '" + first.path() + "' (group '" + first.groupId() + "') and '" + second.path()
							+ "' (group '" + second.groupId() + "')" + suffix);
				}
			}
		}
	}

	private static String platformPathKey(String path, ClientPlatform platform) {
		if (platform != ClientPlatform.WINDOWS && platform != ClientPlatform.MACOS) return path;
		return Normalizer.normalize(path, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
	}

	private static void validateWindowsPath(String groupId, String path, List<String> errors) {
		for (String component : path.split("/")) {
			String trimmed = component.stripTrailing();
			if (trimmed.isEmpty() || !trimmed.equals(component) || component.endsWith(".") || component.matches(".*[<>:\"|?*\\\\\\p{Cntrl}].*"))
				errors.add("windows: group '" + groupId + "' has illegal path component in '" + path + "'");
			String device = trimmed;
			int dot = device.indexOf('.');
			if (dot >= 0) device = device.substring(0, dot);
			String upper = device.toUpperCase(Locale.ROOT);
			if (upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL")
					|| upper.matches("(?:COM|LPT)[1-9]"))
				errors.add("windows: group '" + groupId + "' uses reserved device name in '" + path + "'");
		}
	}

	private record PathOwner(String groupId, String path) {}

	private record PathPair(PathOwner ancestor, PathOwner descendant) {}

	private static void validateReferences(Map<String, GroupManifest.Group> groups, List<String> errors) {
		for (var entry : groups.entrySet()) {
			String id = entry.getKey();
			GroupManifest.Group group = entry.getValue();
			if (group.requires().contains(id)) errors.add("Group '" + id + "' cannot require itself");
			if (group.breaksWith().contains(id)) errors.add("Group '" + id + "' cannot conflict with itself");
			for (String dependency : group.requires()) if (!groups.containsKey(dependency)) errors.add("Group '" + id + "' requires missing group '" + dependency + "'");
			for (String conflict : group.breaksWith()) if (!groups.containsKey(conflict)) errors.add("Group '" + id + "' conflicts with missing group '" + conflict + "'");
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

	private static void validateDefaultAndIndividualSelections(GroupManifest manifest) {
		List<String> errors = new ArrayList<>();
		for (ClientPlatform platform : ClientPlatform.values()) {
			try {
				GroupSelectionResolver.resolveDefault(manifest, platform);
			} catch (SelectionResolutionException e) {
				for (String error : e.errors()) errors.add(platform.id() + ": " + error);
			}
			for (var entry : manifest.groups().entrySet()) {
				if (!entry.getValue().supports(platform)) continue;
				try {
					ResolvedSelection selection = GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of(entry.getKey())), platform);
					if (!selection.selectedGroups().contains(entry.getKey()) && !isOptionalUnavailable(manifest, entry.getKey(), selection))
						errors.add(platform.id() + ": Group '" + entry.getKey() + "' cannot be selected on this platform");
				} catch (SelectionResolutionException e) {
					if (!isOptionalUnavailable(manifest, entry.getKey(), e.resolution())) for (String error : e.errors()) errors.add(platform.id() + ": " + error);
				}
			}
		}
		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
	}

	private static boolean isOptionalUnavailable(GroupManifest manifest, String groupId, ResolvedSelection selection) {
		GroupManifest.Group group = manifest.groups().get(groupId);
		if (group == null || group.required()) return false;
		GroupResolution resolution = selection.resolution(groupId);
		return resolution != null && (resolution.status() == GroupResolution.Status.BLOCKED || resolution.status() == GroupResolution.Status.UNAVAILABLE);
	}

	private static void validateObjectSizes(GroupManifest manifest) {
		Map<String, Long> sizesByHash = new TreeMap<>();
		List<String> errors = new ArrayList<>();
		for (var groupEntry : manifest.groups().entrySet()) for (var fileEntry : groupEntry.getValue().files().entrySet()) {
			GroupManifest.GroupFile file = fileEntry.getValue();
			Long previous = sizesByHash.putIfAbsent(file.sha1(), file.size());
			if (previous != null && previous.longValue() != file.size())
				errors.add("SHA-1 '" + file.sha1() + "' has inconsistent advertised sizes: " + previous + " and " + file.size());
		}
		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
	}

	private static void validateOverlaps(GroupManifest manifest) {
		Map<String, List<Map.Entry<String, GroupManifest.GroupFile>>> byPath = new TreeMap<>();
		for (var groupEntry : manifest.groups().entrySet())
			for (var fileEntry : groupEntry.getValue().files().entrySet())
				byPath.computeIfAbsent(fileEntry.getKey(), ignored -> new ArrayList<>()).add(Map.entry(groupEntry.getKey(), fileEntry.getValue()));

		List<String> errors = new ArrayList<>();
		for (var pathEntry : byPath.entrySet()) {
			List<Map.Entry<String, GroupManifest.GroupFile>> owners = pathEntry.getValue();
			for (int i = 0; i < owners.size(); i++) for (int j = i + 1; j < owners.size(); j++) {
				var first = owners.get(i);
				var second = owners.get(j);
				if (first.getValue().sameEffectiveState(second.getValue())) continue;
				if (coSelectable(manifest, first.getKey(), second.getKey()))
					errors.add("Path '" + pathEntry.getKey() + "' differs between co-selectable groups '"
							+ first.getKey() + "' and '" + second.getKey() + "'");
			}
		}
		if (!errors.isEmpty()) throw new GroupValidationException(errors);
	}

	private static boolean coSelectable(GroupManifest manifest, String first, String second) {
		for (ClientPlatform platform : ClientPlatform.values()) {
			GroupManifest.Group firstGroup = manifest.groups().get(first);
			GroupManifest.Group secondGroup = manifest.groups().get(second);
			if (!firstGroup.supports(platform) || !secondGroup.supports(platform)) continue;
			try {
				ResolvedSelection selection = GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of(first, second)), platform);
				if (selection.selectedGroups().contains(first) && selection.selectedGroups().contains(second)) return true;
			} catch (SelectionResolutionException ignored) {
			}
		}
		return false;
	}

	private static Set<ClientPlatform> validatePlatforms(String groupId, Set<String> input, List<String> errors) {
		if (input == null || input.isEmpty()) return Set.of();
		EnumSet<ClientPlatform> platforms = EnumSet.noneOf(ClientPlatform.class);
		for (String value : input) {
			try {
				platforms.add(ClientPlatform.parse(value));
			} catch (IllegalArgumentException e) {
				errors.add("Group '" + groupId + "' has " + e.getMessage());
			}
		}
		return platforms;
	}

	private static Set<String> validateIds(String description, Set<String> input, List<String> errors) {
		if (input == null) {
			errors.add(description + " is missing");
			return Set.of();
		}
		Set<String> ids = new TreeSet<>();
		for (String id : input) {
			validateId(description, id, errors);
			if (id != null) ids.add(id);
		}
		return ids;
	}

	public static String requireIdentifier(String id) {
		if (!isValidIdentifier(id)) throw new IllegalArgumentException("Invalid group or category ID: " + id);
		return id;
	}

	private static boolean isValidIdentifier(String id) {
		return id != null && ID.matcher(id).matches();
	}

	private static void validateId(String description, String id, List<String> errors) {
		try {
			requireIdentifier(id);
		} catch (IllegalArgumentException e) {
			errors.add("Invalid " + description + " ID: " + id);
		}
	}

	private static String validateCategory(String groupId, String input, List<String> errors) {
		if (input == null || input.isEmpty()) return "";
		if (!isValidIdentifier(input)) errors.add("Invalid Group '" + groupId + "' category ID: " + input);
		return input;
	}

	private static String validateIcon(String groupId, String input, List<String> errors) {
		if (input == null || input.isEmpty()) return "";
		int separator = input.indexOf(':');
		String namespace = separator < 0 ? "" : input.substring(0, separator);
		String path = separator < 0 ? "" : input.substring(separator + 1);
		boolean valid = separator > 0 && separator == input.lastIndexOf(':') && RESOURCE_NAMESPACE.matcher(namespace).matches()
				&& RESOURCE_PATH.matcher(path).matches() && !path.startsWith("/") && !path.endsWith("/") && !path.contains("//")
				&& Arrays.stream(path.split("/", -1)).noneMatch(component -> component.equals(".") || component.equals(".."));
		if (!valid) errors.add("Group '" + groupId + "' has invalid icon resource location: " + input);
		return input;
	}

	private static String value(String value) {
		return value == null ? "" : value;
	}
}
