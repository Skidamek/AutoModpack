package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record ResolvedSelection(
		SelectionIntent intent,
		NavigableSet<String> selectedGroups,
		NavigableSet<String> staleGroups,
		NavigableSet<String> requiredGroups,
		NavigableSet<String> dependencyGroups,
		NavigableSet<String> unavailableGroups,
		NavigableSet<String> requestedUnavailableGroups,
		NavigableMap<String, GroupResolution> groupResolutions) {
	public ResolvedSelection(SelectionIntent intent, Collection<String> selectedGroups, Collection<String> staleGroups) {
		this(intent, immutableSet(selectedGroups), immutableSet(staleGroups), immutableSet(Set.of()), immutableSet(Set.of()), immutableSet(Set.of()),
				immutableSet(Set.of()), immutableMap(Map.of()));
	}

	public ResolvedSelection {
		intent = Objects.requireNonNull(intent);
		selectedGroups = immutableSet(selectedGroups);
		staleGroups = immutableSet(staleGroups);
		requiredGroups = immutableSet(requiredGroups);
		dependencyGroups = immutableSet(dependencyGroups);
		unavailableGroups = immutableSet(unavailableGroups);
		requestedUnavailableGroups = immutableSet(requestedUnavailableGroups);
		groupResolutions = immutableMap(groupResolutions);
	}

	public GroupResolution resolution(String groupId) {
		return groupResolutions.get(groupId);
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
