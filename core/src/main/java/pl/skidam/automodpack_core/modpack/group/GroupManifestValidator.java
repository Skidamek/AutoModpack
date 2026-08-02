package pl.skidam.automodpack_core.modpack.group;

import static pl.skidam.automodpack_core.Constants.modpackCatalogueFileName;
import static pl.skidam.automodpack_core.Constants.modpackContentFileName;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;

public final class GroupManifestValidator {
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
	private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
	private static final Set<String> FILE_TYPES = Set.of("mod", "config", "shader", "resourcepack", "mc_options", "other");

	private GroupManifestValidator() {}

	public static GroupManifest validate(Jsons.CompleteModpackContentFields fields) {
		List<String> errors = new ArrayList<>();
		if (fields == null) throw new GroupValidationException(List.of("Complete modpack catalogue is missing"));
		if (!ModpackId.isValid(fields.modpackId)) errors.add("Invalid modpack ID");
		if (fields.groups == null || fields.groups.isEmpty()) errors.add("Group catalogue is empty");
		if (fields.selectionTags == null) errors.add("Selection tags are missing");

		Map<String, GroupManifest.SelectionTag> tags = new TreeMap<>();
		if (fields.selectionTags != null) for (var entry : fields.selectionTags.entrySet()) {
			String id = entry.getKey();
			Jsons.CompleteModpackContentFields.SelectionTagFields tag = entry.getValue();
			if (!isValidIdentifier(id)) {
				errors.add("Invalid selection tag ID: " + id);
				continue;
			}
			if (tag == null) {
				errors.add("Selection tag '" + id + "' is missing its declaration");
				continue;
			}
			tags.put(id, new GroupManifest.SelectionTag(tag.displayName, tag.description, tag.defaultSelected, tag.serverForced));
		}

		Map<String, GroupManifest.Group> groups = new TreeMap<>();
		if (fields.groups != null) for (var entry : fields.groups.entrySet()) {
			String id = entry.getKey();
			Jsons.CompleteModpackContentFields.ModpackGroupFields group = entry.getValue();
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
			String tag = validateOptionalTag(id, group.tag, errors);
			Set<ClientPlatform> platforms = validatePlatforms(id, group.compatiblePlatforms, errors);
			Map<String, GroupManifest.GroupFile> files = validateFiles(id, group.files, errors);
			groups.put(id, new GroupManifest.Group(group.displayName, group.description, tag, group.required, group.recommended,
					new TreeSet<>(breaksWith), new TreeSet<>(requires), platforms, new TreeMap<>(files)));
		}

		validateReferences(groups, tags, errors);
		validateCycles(groups, errors);

		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
		GroupManifest manifest = new GroupManifest(fields.modpackId, value(fields.modpackName), value(fields.automodpackVersion), value(fields.loader),
				value(fields.loaderVersion), value(fields.mcVersion), new TreeMap<>(groups), new TreeMap<>(tags));
		validatePlatformPaths(manifest, errors);
		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
		validateDefaultAndIndividualSelections(manifest);
		validateObjectSizes(manifest);
		validateOverlaps(manifest);
		return manifest;
	}

	private static Map<String, GroupManifest.GroupFile> validateFiles(String groupId,
			Map<String, Jsons.CompleteModpackContentFields.GroupFileFields> input, List<String> errors) {
		Map<String, GroupManifest.GroupFile> files = new TreeMap<>();
		if (input == null) {
			errors.add("Group '" + groupId + "' files are missing");
			return files;
		}
		for (var entry : input.entrySet()) {
			String path = entry.getKey();
			if (!isCanonicalPublishedPath(path, groupId, errors)) continue;
			Jsons.CompleteModpackContentFields.GroupFileFields file = entry.getValue();
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
			if (file.type == null || !FILE_TYPES.contains(file.type)) errors.add("Group '" + groupId + "' file '" + path + "' has invalid type");
			if (file.sha1 == null || !SHA1.matcher(file.sha1).matches()) errors.add("Group '" + groupId + "' file '" + path + "' has invalid SHA-1");
			if (file.overwriteEditable && !file.editable) errors.add("Group '" + groupId + "' file '" + path + "' overwrites edits but is not editable");
			files.put(path, new GroupManifest.GroupFile(size, file.type, file.editable, file.overwriteEditable, file.forceCopy,
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
		if (path.equalsIgnoreCase(modpackContentFileName.toString()) || path.equalsIgnoreCase(modpackCatalogueFileName.toString())) {
			errors.add("Group '" + groupId + "' reserves AutoModpack metadata path: " + path);
			return false;
		}
		return true;
	}

	private static void validatePlatformPaths(GroupManifest manifest, List<String> errors) {
		for (ClientPlatform platform : ClientPlatform.values()) {
			Map<String, List<PathOwner>> aliases = new TreeMap<>();
			for (var groupEntry : manifest.groups().entrySet()) {
				String groupId = groupEntry.getKey();
				GroupManifest.Group group = groupEntry.getValue();
				if (!group.supports(platform)) continue;
				for (String path : group.files().keySet()) {
					if (platform == ClientPlatform.WINDOWS) validateWindowsPath(groupId, path, errors);
					aliases.computeIfAbsent(platformPathKey(path, platform), ignored -> new ArrayList<>()).add(new PathOwner(groupId, path));
				}
			}
			for (List<PathOwner> owners : aliases.values()) {
				for (int i = 0; i < owners.size(); i++) for (int j = i + 1; j < owners.size(); j++) {
					PathOwner first = owners.get(i);
					PathOwner second = owners.get(j);
					if (first.path().equals(second.path())) continue;
					if (first.groupId().equals(second.groupId()) || coSelectable(manifest, first.groupId(), second.groupId()))
						errors.add(platform.id() + ": paths '" + first.path() + "' (group '" + first.groupId() + "') and '" + second.path()
								+ "' (group '" + second.groupId() + "') alias on this platform");
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

	private static void validateReferences(Map<String, GroupManifest.Group> groups, Map<String, GroupManifest.SelectionTag> tags, List<String> errors) {
		for (var entry : groups.entrySet()) {
			String id = entry.getKey();
			GroupManifest.Group group = entry.getValue();
			if (group.requires().contains(id)) errors.add("Group '" + id + "' cannot require itself");
			if (group.breaksWith().contains(id)) errors.add("Group '" + id + "' cannot conflict with itself");
			for (String dependency : group.requires()) if (!groups.containsKey(dependency)) errors.add("Group '" + id + "' requires missing group '" + dependency + "'");
			for (String conflict : group.breaksWith()) if (!groups.containsKey(conflict)) errors.add("Group '" + id + "' conflicts with missing group '" + conflict + "'");
			if (!group.tag().isEmpty() && !tags.containsKey(group.tag())) errors.add("Group '" + id + "' uses missing selection tag '" + group.tag() + "'");
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
		SelectionIntent defaultIntent = GroupSelectionResolver.defaultIntent(manifest);
		for (ClientPlatform platform : ClientPlatform.values()) {
			try {
				GroupSelectionResolver.resolve(manifest, defaultIntent, platform);
			} catch (SelectionResolutionException e) {
				for (String error : e.errors()) errors.add(platform.id() + ": " + error);
			}
			for (var entry : manifest.groups().entrySet()) {
				if (!entry.getValue().supports(platform)) continue;
				try {
					ResolvedSelection selection = GroupSelectionResolver.resolve(manifest, new SelectionIntent(Set.of(entry.getKey())), platform);
					if (!selection.selectedGroups().contains(entry.getKey()))
						errors.add(platform.id() + ": Group '" + entry.getKey() + "' cannot be selected on this platform");
				} catch (SelectionResolutionException e) {
					for (String error : e.errors()) errors.add(platform.id() + ": " + error);
				}
			}
		}
		if (!errors.isEmpty()) throw new GroupValidationException(errors.stream().distinct().sorted().toList());
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
		if (!isValidIdentifier(id)) throw new IllegalArgumentException("Invalid group or tag ID: " + id);
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

	private static String validateOptionalTag(String groupId, String input, List<String> errors) {
		if (input == null || input.isEmpty()) return "";
		if (!isValidIdentifier(input)) errors.add("Invalid Group '" + groupId + "' selection tag ID: " + input);
		return input;
	}

	private static String value(String value) {
		return value == null ? "" : value;
	}
}
