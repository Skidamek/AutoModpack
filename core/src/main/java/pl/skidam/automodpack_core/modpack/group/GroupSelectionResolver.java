package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public final class GroupSelectionResolver {
	private GroupSelectionResolver() {}

	public static SelectionIntent defaultIntent(GroupManifest manifest) {
		Set<String> requested = new TreeSet<>();
		Set<String> defaultTags = new HashSet<>();
		for (var entry : manifest.selectionTags().entrySet()) if (entry.getValue().defaultSelected()) defaultTags.add(entry.getKey());
		for (var entry : manifest.groups().entrySet()) {
			GroupManifest.Group group = entry.getValue();
			if (group.recommended() || group.tags().stream().anyMatch(defaultTags::contains)) requested.add(entry.getKey());
		}
		return new SelectionIntent(requested);
	}

	public static ResolvedSelection resolve(GroupManifest manifest, SelectionIntent intent, ClientPlatform platform) {
		Objects.requireNonNull(manifest);
		Objects.requireNonNull(intent);
		Objects.requireNonNull(platform);
		List<String> errors = new ArrayList<>();
		Set<String> stale = new TreeSet<>();
		Set<String> selected = new TreeSet<>();

		Set<String> forced = new TreeSet<>();
		for (var entry : manifest.groups().entrySet()) {
			GroupManifest.Group group = entry.getValue();
			if (group.required() || group.tags().stream().anyMatch(tag -> manifest.selectionTags().get(tag).serverForced())) forced.add(entry.getKey());
		}
		for (String groupId : forced) addClosure(manifest, groupId, platform, true, selected, errors, new HashSet<>());

		for (String requested : intent.requestedGroups()) {
			if (!manifest.groups().containsKey(requested)) {
				stale.add(requested);
				continue;
			}
			Set<String> candidate = new TreeSet<>();
			if (addClosure(manifest, requested, platform, false, candidate, errors, new HashSet<>())) selected.addAll(candidate);
		}

		List<String> ordered = new ArrayList<>(selected);
		for (int i = 0; i < ordered.size(); i++) {
			for (int j = i + 1; j < ordered.size(); j++) {
				String first = ordered.get(i);
				String second = ordered.get(j);
				if (conflicts(manifest, first, second)) errors.add("Groups '" + first + "' and '" + second + "' cannot be selected together");
			}
		}

		if (!errors.isEmpty()) throw new SelectionResolutionException(errors.stream().distinct().sorted().toList());
		return new ResolvedSelection(intent, new TreeSet<>(selected), new TreeSet<>(stale));
	}

	private static boolean addClosure(GroupManifest manifest, String groupId, ClientPlatform platform, boolean required, Set<String> selected,
			List<String> errors, Set<String> active) {
		GroupManifest.Group group = manifest.groups().get(groupId);
		if (group == null) {
			errors.add("Group '" + groupId + "' is missing");
			return false;
		}
		if (!group.supports(platform)) {
			if (required) errors.add("Group '" + groupId + "' is unavailable on " + platform.id());
			return false;
		}
		if (!active.add(groupId)) {
			errors.add("Group dependency cycle includes '" + groupId + "'");
			return false;
		}
		try {
			for (String dependency : group.requires()) {
				if (!addClosure(manifest, dependency, platform, required, selected, errors, active)) return false;
			}
			selected.add(groupId);
			return true;
		} finally {
			active.remove(groupId);
		}
	}

	public static boolean conflicts(GroupManifest manifest, String first, String second) {
		if (Objects.equals(first, second)) return false;
		GroupManifest.Group firstGroup = manifest.groups().get(first);
		GroupManifest.Group secondGroup = manifest.groups().get(second);
		return firstGroup != null && firstGroup.breaksWith().contains(second) || secondGroup != null && secondGroup.breaksWith().contains(first);
	}
}
