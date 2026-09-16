package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record ResolvedSelection(SelectionIntent intent, NavigableSet<String> selectedGroups, NavigableSet<String> staleRequestedGroups) {
	public ResolvedSelection {
		selectedGroups = immutableSet(selectedGroups);
		staleRequestedGroups = immutableSet(staleRequestedGroups);
	}

	private static NavigableSet<String> immutableSet(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) sorted.addAll(values);
		return Collections.unmodifiableNavigableSet(sorted);
	}
}
