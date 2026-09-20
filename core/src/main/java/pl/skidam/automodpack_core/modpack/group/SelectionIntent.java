package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record SelectionIntent(NavigableSet<String> requestedGroups, NavigableSet<String> requestedCategories, NavigableSet<String> excludedGroups, ClientPlatform platform) {
	public SelectionIntent(Collection<String> requestedGroups) {
		this(requestedGroups, Set.of(), Set.of());
	}

	public SelectionIntent(Collection<String> requestedGroups, Collection<String> requestedCategories, Collection<String> excludedGroups) {
		this(requestedGroups, requestedCategories, excludedGroups, null);
	}

	public SelectionIntent(Collection<String> requestedGroups, Collection<String> requestedCategories, Collection<String> excludedGroups, ClientPlatform platform) {
		this(toGroupIdSet(requestedGroups), toNameSet(requestedCategories), toGroupIdSet(excludedGroups), platform);
	}

	public SelectionIntent {
		requestedGroups = toGroupIdSet(requestedGroups);
		requestedCategories = toNameSet(requestedCategories);
		excludedGroups = toGroupIdSet(excludedGroups);
	}

	/** Returns the same choice carrying a per-pack platform override; null keeps following the detected platform. */
	public SelectionIntent withPlatform(ClientPlatform platform) {
		return new SelectionIntent(requestedGroups, requestedCategories, excludedGroups, platform);
	}

	// The platform rides along with the choice but never affects choice equality: recovery flows rebuild
	// intents without it and must keep comparing equal to the stored selection, and a transaction commit
	// may overwrite the stored platform with the platform its plan resolved under.
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

	private static NavigableSet<String> toGroupIdSet(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) for (String value : values) sorted.add(GroupManifestValidator.requireIdentifier(value));
		return Collections.unmodifiableNavigableSet(sorted);
	}

	/** Category entries are player-facing display names, so unlike group ids they skip the ID pattern. */
	private static NavigableSet<String> toNameSet(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) sorted.addAll(values);
		return Collections.unmodifiableNavigableSet(sorted);
	}
}
