package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record SelectionIntent(NavigableSet<String> requestedGroups, NavigableSet<String> requestedCategories, NavigableSet<String> excludedGroups, ClientPlatform platform) {
	public SelectionIntent(Collection<String> requestedGroups) {
		this(requestedGroups, Set.of(), Set.of());
	}

	public SelectionIntent(Collection<String> requestedGroups, Collection<String> excludedGroups) {
		this(requestedGroups, Set.of(), excludedGroups);
	}

	public SelectionIntent(Collection<String> requestedGroups, Collection<String> requestedCategories, Collection<String> excludedGroups) {
		this(requestedGroups, requestedCategories, excludedGroups, null);
	}

	public SelectionIntent(Collection<String> requestedGroups, Collection<String> requestedCategories, Collection<String> excludedGroups, ClientPlatform platform) {
		this(toSortedSet(requestedGroups), toSortedSet(requestedCategories), toSortedSet(excludedGroups), platform);
	}

	public SelectionIntent {
		requestedGroups = toSortedSet(requestedGroups);
		requestedCategories = toSortedSet(requestedCategories);
		excludedGroups = toSortedSet(excludedGroups);
	}

	/** Returns the same choice carrying a per-pack platform override; null keeps following the detected platform. */
	public SelectionIntent withPlatform(ClientPlatform platform) {
		return new SelectionIntent(requestedGroups, requestedCategories, excludedGroups, platform);
	}

	// The platform override rides along with the choice but never affects choice equality: transaction and
	// recovery flows rebuild intents without it and must keep comparing equal to the stored selection.
	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof SelectionIntent intent)) return false;
		return requestedGroups.equals(intent.requestedGroups) && requestedCategories.equals(intent.requestedCategories) && excludedGroups.equals(intent.excludedGroups);
	}

	@Override
	public int hashCode() {
		return Objects.hash(requestedGroups, requestedCategories, excludedGroups);
	}

	private static NavigableSet<String> toSortedSet(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) for (String value : values) sorted.add(GroupManifestValidator.requireIdentifier(value));
		return Collections.unmodifiableNavigableSet(sorted);
	}
}
