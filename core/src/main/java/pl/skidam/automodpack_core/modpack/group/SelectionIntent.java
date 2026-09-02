package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record SelectionIntent(NavigableSet<String> requestedTags, NavigableSet<String> requestedGroups, NavigableSet<String> excludedGroups) {
	public SelectionIntent(Collection<String> requestedGroups) {
		this(Set.of(), requestedGroups, Set.of());
	}

	public SelectionIntent(Collection<String> requestedTags, Collection<String> requestedGroups) {
		this(requestedTags, requestedGroups, Set.of());
	}

	public SelectionIntent(Collection<String> requestedTags, Collection<String> requestedGroups, Collection<String> excludedGroups) {
		this(toSortedSet(requestedTags), toSortedSet(requestedGroups), toSortedSet(excludedGroups));
	}

	public SelectionIntent {
		requestedTags = toSortedSet(requestedTags);
		requestedGroups = toSortedSet(requestedGroups);
		excludedGroups = toSortedSet(excludedGroups);
	}

	private static NavigableSet<String> toSortedSet(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) for (String value : values) sorted.add(GroupManifestValidator.requireIdentifier(value));
		return Collections.unmodifiableNavigableSet(sorted);
	}
}
