package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record ResolvedSelection(
		SelectionIntent intent,
		NavigableSet<String> selectedGroups,
		NavigableSet<String> staleRequestedGroups,
		NavigableSet<String> requiredGroups,
		NavigableSet<String> forcedGroups,
		NavigableSet<String> dependencyGroups,
		NavigableSet<String> unavailableGroups,
		NavigableSet<String> requestedUnavailableGroups,
		NavigableMap<String, GroupResolution> groupResolutions) {
	public ResolvedSelection(SelectionIntent intent, Collection<String> selectedGroups, Collection<String> staleRequestedGroups) {
		this(intent, immutableSet(selectedGroups), immutableSet(staleRequestedGroups), immutableSet(Set.of()), immutableSet(Set.of()), immutableSet(Set.of()), immutableSet(Set.of()),
				immutableSet(Set.of()), immutableMap(Map.of()));
	}

	public ResolvedSelection {
		intent = Objects.requireNonNull(intent);
		selectedGroups = immutableSet(selectedGroups);
		staleRequestedGroups = immutableSet(staleRequestedGroups);
		requiredGroups = immutableSet(requiredGroups);
		forcedGroups = immutableSet(forcedGroups);
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
