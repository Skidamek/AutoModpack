package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record SelectionIntent(NavigableSet<String> requestedGroups, NavigableSet<String> requestedTags, NavigableSet<String> excludedGroups) {
	public SelectionIntent(Collection<String> requestedGroups) {
		this(requestedGroups, Set.of(), Set.of());
	}

	public SelectionIntent(Collection<String> requestedGroups, Collection<String> excludedGroups) {
		this(requestedGroups, Set.of(), excludedGroups);
	}

	public SelectionIntent(Collection<String> requestedGroups, Collection<String> requestedTags, Collection<String> excludedGroups) {
		this(toSortedSet(requestedGroups), toSortedSet(requestedTags), toSortedSet(excludedGroups));
	}

	public SelectionIntent {
		requestedGroups = toSortedSet(requestedGroups);
		requestedTags = toSortedSet(requestedTags);
		excludedGroups = toSortedSet(excludedGroups);
	}

	private static NavigableSet<String> toSortedSet(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) for (String value : values) sorted.add(GroupManifestValidator.requireIdentifier(value));
		return Collections.unmodifiableNavigableSet(sorted);
	}
}
