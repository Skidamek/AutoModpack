package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public record GroupResolution(String groupId, Status status, NavigableSet<Reason> reasons, NavigableSet<String> relatedGroups, String explanation) {
	public GroupResolution(String groupId, Status status, Collection<Reason> reasons, Collection<String> relatedGroups, String explanation) {
		this(groupId, status, immutableReasons(reasons), immutableStrings(relatedGroups), explanation);
	}

	public GroupResolution {
		groupId = Objects.requireNonNull(groupId);
		status = Objects.requireNonNull(status);
		reasons = immutableReasons(reasons);
		relatedGroups = immutableStrings(relatedGroups);
		explanation = explanation == null ? "" : explanation;
	}

	public boolean selected() {
		return status == Status.SELECTED;
	}

	private static NavigableSet<Reason> immutableReasons(Collection<Reason> values) {
		TreeSet<Reason> sorted = new TreeSet<>(Comparator.comparing(Enum::name));
		if (values != null) sorted.addAll(values);
		return Collections.unmodifiableNavigableSet(sorted);
	}

	private static NavigableSet<String> immutableStrings(Collection<String> values) {
		TreeSet<String> sorted = new TreeSet<>();
		if (values != null) sorted.addAll(values);
		return Collections.unmodifiableNavigableSet(sorted);
	}

	public enum Status {
		SELECTED,
		AVAILABLE,
		UNAVAILABLE,
		BLOCKED,
		EXCLUDED,
		CONFLICT,
		STALE
	}

	public enum Reason {
		EXPLICIT_GROUP,
		REQUIRED,
		FORCED,
		DEFAULT_SELECTED,
		DEPENDENCY,
		EXPLICIT_EXCLUSION,
		PLATFORM_INCOMPATIBLE,
		EXPLICIT_REQUEST_UNAVAILABLE,
		BLOCKED_BY_DEPENDENCY,
		CONFLICTING_GROUP,
		STALE_SELECTION
	}
}
