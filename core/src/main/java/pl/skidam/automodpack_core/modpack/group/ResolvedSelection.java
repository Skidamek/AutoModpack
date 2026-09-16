package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record ResolvedSelection(
		SelectionIntent intent,
		NavigableSet<String> selectedGroups,
		NavigableSet<String> staleRequestedGroups,
		NavigableSet<String> staleRequestedTags,
		NavigableSet<String> requiredGroups,
		NavigableSet<String> forcedGroups,
		NavigableSet<String> dependencyGroups,
		NavigableSet<String> tagSelectedGroups,
		NavigableSet<String> unavailableGroups,
		NavigableMap<String, GroupResolution> groupResolutions) {
	public ResolvedSelection(SelectionIntent intent, Collection<String> selectedGroups, Collection<String> staleRequestedGroups) {
		this(intent, immutableSet(selectedGroups), immutableSet(staleRequestedGroups), immutableSet(Set.of()), immutableSet(Set.of()), immutableSet(Set.of()), immutableSet(Set.of()),
				immutableSet(Set.of()), immutableSet(Set.of()), immutableMap(Map.of()));
	}

	public ResolvedSelection {
		intent = Objects.requireNonNull(intent);
		selectedGroups = immutableSet(selectedGroups);
		staleRequestedGroups = immutableSet(staleRequestedGroups);
		staleRequestedTags = immutableSet(staleRequestedTags);
		requiredGroups = immutableSet(requiredGroups);
		forcedGroups = immutableSet(forcedGroups);
		dependencyGroups = immutableSet(dependencyGroups);
		tagSelectedGroups = immutableSet(tagSelectedGroups);
		unavailableGroups = immutableSet(unavailableGroups);
		groupResolutions = immutableMap(groupResolutions);
	}

	public NavigableMap<String, GroupResolution> explanations() {
		return groupResolutions;
	}

	public GroupResolution explanation(String groupId) {
		return groupResolutions.get(groupId);
	}

	public NavigableSet<String> unsupportedGroups() {
		return unavailableGroups;
	}

	public NavigableSet<String> tagExpandedGroups() {
		return tagSelectedGroups;
	}

	private static NavigableSet<String> immutableSet(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) sorted.addAll(values);
		return Collections.unmodifiableNavigableSet(sorted);
	}

	private static NavigableMap<String, GroupResolution> immutableMap(Map<String, GroupResolution> values) {
		TreeMap<String, GroupResolution> sorted = new TreeMap<>();
		if (values != null) sorted.putAll(values);
		return Collections.unmodifiableNavigableMap(sorted);
	}
}
