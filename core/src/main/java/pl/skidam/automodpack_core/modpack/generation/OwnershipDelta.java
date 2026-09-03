package pl.skidam.automodpack_core.modpack.generation;

import java.util.*;

import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Path-level changes used to materialize one cumulative ownership ledger. */
public record OwnershipDelta(String modpackId, NavigableMap<String, Change> changes, String digest) {
	private static final Comparator<OwnershipLedger.Content> CONTENT_ORDER = Comparator.comparing(OwnershipLedger.Content::sha1)
			.thenComparingLong(OwnershipLedger.Content::size);

	private static final String DIGEST_DOMAIN = "automodpack-ownership-delta-v1";

	public enum Kind {
		ADDED, REPLACED, REMOVED, RETURNED, GROUP_OWNERSHIP_CHANGED
	}

	public record Change(String logicalPath, Kind kind, OwnershipLedger.Content content, NavigableSet<OwnershipLedger.Content> contents,
			NavigableSet<String> groupIds) {
		public Change {
			logicalPath = LogicalPath.normalize(logicalPath);
			kind = Objects.requireNonNull(kind);
			content = Objects.requireNonNull(content);
			contents = immutableContents(contents);
			if (!contents.isEmpty() && !contents.contains(content)) throw new IllegalArgumentException("Change content is not one of its variants");
			groupIds = immutableStrings(groupIds);
		}

		private static NavigableSet<OwnershipLedger.Content> immutableContents(Collection<OwnershipLedger.Content> values) {
			TreeSet<OwnershipLedger.Content> sorted = new TreeSet<>(CONTENT_ORDER);
			if (values != null) sorted.addAll(values);
			return Collections.unmodifiableNavigableSet(sorted);
		}

		private static NavigableSet<String> immutableStrings(Collection<String> values) {
			TreeSet<String> sorted = new TreeSet<>();
			if (values != null) sorted.addAll(values);
			return Collections.unmodifiableNavigableSet(sorted);
		}
	}

	public OwnershipDelta {
		ModpackId.requireValid(modpackId);
		changes = immutableChanges(changes);
		String expected = digest(modpackId, changes);
		if (digest == null || !digest.equals(expected)) throw new IllegalArgumentException("Ownership delta digest does not match content");
	}

	public OwnershipDelta(String modpackId, Map<String, Change> changes) {
		this(modpackId, toNavigableMap(changes), digest(modpackId, changes));
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
					changes.put(path, new Change(path, Kind.REMOVED, old.historicalHashes().first(), singletonContents(old.historicalHashes().first()), old.historicalGroupIds()));
				continue;
			}
			if (old == null) changes.put(path, new Change(path, Kind.ADDED, now.content(), now.contents(), now.groups()));
			else
				if (old.currentStatus() == OwnershipLedger.Status.TOMBSTONE)
					changes.put(path, new Change(path, Kind.RETURNED, now.content(), now.contents(), now.groups()));
				else
					if (!old.historicalHashes().containsAll(now.contents()))
						changes.put(path, new Change(path, Kind.REPLACED, now.content(), now.contents(), now.groups()));
					else
						if (!old.historicalGroupIds().containsAll(now.groups()))
							changes.put(path, new Change(path, Kind.GROUP_OWNERSHIP_CHANGED, now.content(), now.contents(), now.groups()));
		}
		return new OwnershipDelta(manifest.modpackId(), new TreeMap<>(changes));
	}

	private static NavigableSet<OwnershipLedger.Content> singletonContents(OwnershipLedger.Content content) {
		TreeSet<OwnershipLedger.Content> contents = new TreeSet<>(CONTENT_ORDER);
		contents.add(content);
		return contents;
	}

	public static String digest(String modpackId, Map<String, Change> changes) {
		ModpackId.requireValid(modpackId);
		TreeMap<String, Change> sorted = new TreeMap<>();
		if (changes != null) sorted.putAll(changes);
		CanonicalEncoder encoder = new CanonicalEncoder().string(DIGEST_DOMAIN).string(modpackId).integer(sorted.size());
		for (Change change : sorted.values()) {
			encoder.string(change.logicalPath()).string(change.kind().name()).string(change.content().sha1()).longValue(change.content().size());
			encoder.integer(change.contents().size());
			for (OwnershipLedger.Content content : change.contents()) encoder.string(content.sha1()).longValue(content.size());
			encoder.integer(change.groupIds().size());
			for (String groupId : change.groupIds()) encoder.string(groupId);
		}
		return HashUtils.sha1(encoder.bytes());
	}

	private record Current(NavigableSet<OwnershipLedger.Content> contents, NavigableSet<String> groups) {
		private OwnershipLedger.Content content() {
			return contents.first();
		}
	}

	private static Map<String, Current> current(GroupManifest manifest) {
		Map<String, Current> result = new TreeMap<>();
		for (var group : manifest.groups().entrySet()) for (var file : group.getValue().files().entrySet()) {
			String path = LogicalPath.normalize(file.getKey());
			OwnershipLedger.Content content = new OwnershipLedger.Content(file.getValue().sha1().toLowerCase(Locale.ROOT), file.getValue().size());
			Current previous = result.get(path);
			TreeSet<OwnershipLedger.Content> contents = new TreeSet<>(CONTENT_ORDER);
			if (previous != null) contents.addAll(previous.contents());
			contents.add(content);
			TreeSet<String> groups = new TreeSet<>(previous == null ? Set.of() : previous.groups());
			groups.add(group.getKey());
			result.put(path, new Current(Collections.unmodifiableNavigableSet(contents), Collections.unmodifiableNavigableSet(groups)));
		}
		return result;
	}

	private static NavigableMap<String, Change> immutableChanges(Map<String, Change> values) {
		TreeMap<String, Change> sorted = new TreeMap<>();
		if (values != null) sorted.putAll(values);
		return Collections.unmodifiableNavigableMap(sorted);
	}

	private static NavigableMap<String, Change> toNavigableMap(Map<String, Change> values) {
		return values == null ? new TreeMap<>() : new TreeMap<>(values);
	}

}
