package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record SelectionIntent(NavigableSet<String> requestedGroups) {
	public SelectionIntent(Collection<String> requestedGroups) {
		this(toSortedSet(requestedGroups));
	}

	public SelectionIntent {
		requestedGroups = toSortedSet(requestedGroups);
	}

	private static NavigableSet<String> toSortedSet(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) for (String value : values) sorted.add(GroupManifestValidator.requireIdentifier(value));
		return Collections.unmodifiableNavigableSet(sorted);
	}
}
