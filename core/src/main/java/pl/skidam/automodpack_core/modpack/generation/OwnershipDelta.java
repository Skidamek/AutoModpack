package pl.skidam.automodpack_core.modpack.generation;

import java.util.*;

import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;

/** Path-level changes used to materialize one cumulative ownership ledger. */
public record OwnershipDelta(String modpackId, NavigableMap<String, Change> changes) {
	public enum Kind {
		ADDED, REPLACED, REMOVED, RETURNED, GROUP_OWNERSHIP_CHANGED
	}
	public record Change(String logicalPath, Kind kind, OwnershipLedger.Content content, NavigableSet<String> groupIds) {
		public Change {
			logicalPath = LogicalPath.normalize(logicalPath);
			content = Objects.requireNonNull(content);
			groupIds = Collections.unmodifiableNavigableSet(new TreeSet<>(groupIds == null ? Set.of() : groupIds));
			kind = Objects.requireNonNull(kind);
		}
	}

	public OwnershipDelta {
		changes = immutableChanges(changes);
	}

	public static OwnershipDelta between(OwnershipLedger parent, GroupManifest manifest) {
		Objects.requireNonNull(parent);
		Objects.requireNonNull(manifest);
		Map<String, Current> current = current(manifest);
		Map<String, Change> changes = new TreeMap<>();
		Set<String> paths = new TreeSet<>(parent.entries().keySet());
		paths.addAll(current.keySet());
		for (String path : paths) {
			OwnershipLedger.Entry old = parent.entries().get(path);
			Current now = current.get(path);
			if (now == null) {
				if (old != null && old.currentStatus() == OwnershipLedger.Status.PRESENT)
					changes.put(path, new Change(path, Kind.REMOVED, old.historicalHashes().first(), old.historicalGroupIds()));
				continue;
			}
			if (old == null) changes.put(path, new Change(path, Kind.ADDED, now.content(), now.groups()));
			else
				if (old.currentStatus() == OwnershipLedger.Status.TOMBSTONE)
					changes.put(path, new Change(path, Kind.RETURNED, now.content(), now.groups()));
				else
					if (!old.historicalHashes().contains(now.content()))
						changes.put(path, new Change(path, Kind.REPLACED, now.content(), now.groups()));
					else
						if (!old.historicalGroupIds().containsAll(now.groups()))
							changes.put(path, new Change(path, Kind.GROUP_OWNERSHIP_CHANGED, now.content(), now.groups()));
		}
		return new OwnershipDelta(manifest.modpackId(), new TreeMap<>(changes));
	}

	private record Current(OwnershipLedger.Content content, NavigableSet<String> groups) {}

	private static Map<String, Current> current(GroupManifest manifest) {
		Map<String, Current> result = new TreeMap<>();
		for (var group : manifest.groups().entrySet()) for (var file : group.getValue().files().entrySet()) {
			String path = LogicalPath.normalize(file.getKey());
			OwnershipLedger.Content content = new OwnershipLedger.Content(file.getValue().sha1().toLowerCase(Locale.ROOT), file.getValue().size());
			Current previous = result.get(path);
			if (previous != null && !previous.content().equals(content)) throw new IllegalArgumentException("Conflicting current ownership for path: " + path);
			TreeSet<String> groups = new TreeSet<>(previous == null ? Set.of() : previous.groups());
			groups.add(group.getKey());
			result.put(path, new Current(content, Collections.unmodifiableNavigableSet(groups)));
		}
		return result;
	}

	private static NavigableMap<String, Change> immutableChanges(Map<String, Change> values) {
		TreeMap<String, Change> sorted = new TreeMap<>();
		if (values != null) sorted.putAll(values);
		return Collections.unmodifiableNavigableMap(sorted);
	}
}
