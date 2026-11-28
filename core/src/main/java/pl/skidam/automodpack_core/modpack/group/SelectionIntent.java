package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

/**
 * Sparse user intent over a pack's choosable groups. Empty {@code enabled} and {@code disabled} ({@link #DEFAULTS})
 * means "follow server defaults", so newly published {@code RECOMMENDED} groups arrive selected automatically for
 * existing players without touching saved preferences.
 */
public record SelectionIntent(NavigableSet<String> enabled, NavigableSet<String> disabled, ClientPlatform platform) {
	public static final SelectionIntent DEFAULTS = new SelectionIntent(Set.of(), Set.of(), null);

	public static SelectionIntent ofEnabled(Collection<String> enabled) {
		return new SelectionIntent(enabled, Set.of(), null);
	}

	public SelectionIntent(Collection<String> enabled, Collection<String> disabled) {
		this(enabled, disabled, null);
	}

	public SelectionIntent(Collection<String> enabled, Collection<String> disabled, ClientPlatform platform) {
		this(toGroupIdSet(enabled), toGroupIdSet(disabled), platform);
	}

	public SelectionIntent {
		enabled = toGroupIdSet(enabled);
		disabled = toGroupIdSet(disabled);
		if (!Collections.disjoint(enabled, disabled)) {
			Set<String> overlap = new TreeSet<>(enabled);
			overlap.retainAll(disabled);
			throw new IllegalArgumentException("SelectionIntent cannot both enable and disable: " + overlap);
		}
	}

	public boolean isDefaults() {
		return enabled.isEmpty() && disabled.isEmpty();
	}

	/** Returns the same choice carrying a per-pack platform override; null keeps following the detected platform. */
	public SelectionIntent withPlatform(ClientPlatform platform) {
		return new SelectionIntent(enabled, disabled, platform);
	}

	// The platform rides along with the choice without affecting choice equality: recovery flows rebuild intents without
	// it and ModpackUpdater compares the platform override separately.
	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof SelectionIntent intent)) return false;
		return enabled.equals(intent.enabled) && disabled.equals(intent.disabled);
	}

	@Override
	public int hashCode() {
		return Objects.hash(enabled, disabled);
	}

	private static NavigableSet<String> toGroupIdSet(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) for (String value : values) sorted.add(GroupManifestValidator.requireIdentifier(value));
		return Collections.unmodifiableNavigableSet(sorted);
	}
}
