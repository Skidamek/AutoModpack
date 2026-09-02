package pl.skidam.automodpack_core.modpack.generation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;

/** Immutable cumulative ownership projection for one modpack lineage. */
public record OwnershipLedger(String modpackId, NavigableMap<String, Entry> entries, String digest) {
	private static final Comparator<Content> CONTENT_ORDER = Comparator.comparing(Content::sha1).thenComparingLong(Content::size);
	private static final String PROVISIONAL_GENERATION = "0".repeat(40);

	public enum Status {
		PRESENT, TOMBSTONE
	}

	public record Content(String sha1, long size) {
		public Content {
			if (sha1 == null || !sha1.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid ledger SHA-1");
			if (size < 0) throw new IllegalArgumentException("Negative ledger content size");
		}
	}

	public record Entry(String logicalPath, NavigableSet<Content> historicalHashes, NavigableSet<String> historicalGroupIds,
			String firstPublishedGenerationId, String lastPublishedGenerationId, Status currentStatus) {
		public Entry {
			logicalPath = LogicalPath.normalize(logicalPath);
			historicalHashes = immutableContents(historicalHashes);
			historicalGroupIds = immutableStrings(historicalGroupIds);
			firstPublishedGenerationId = requireGenerationReference(firstPublishedGenerationId, "first published generation ID");
			lastPublishedGenerationId = requireGenerationReference(lastPublishedGenerationId, "last published generation ID");
			currentStatus = Objects.requireNonNull(currentStatus, "current status");
			if (historicalHashes.isEmpty()) throw new IllegalArgumentException("Ledger entry has no historical hashes");
			if (historicalGroupIds.isEmpty()) throw new IllegalArgumentException("Ledger entry has no historical groups");
		}

		public Entry(String logicalPath, Collection<Content> historicalHashes, Collection<String> historicalGroupIds,
				String firstPublishedGenerationId, String lastPublishedGenerationId, Status currentStatus) {
			this(logicalPath, immutableContents(historicalHashes), immutableStrings(historicalGroupIds), firstPublishedGenerationId, lastPublishedGenerationId, currentStatus);
		}

		private static NavigableSet<Content> immutableContents(Collection<Content> values) {
			TreeSet<Content> result = new TreeSet<>(CONTENT_ORDER);
			if (values != null) result.addAll(values);
			return Collections.unmodifiableNavigableSet(result);
		}

		private static NavigableSet<String> immutableStrings(Collection<String> values) {
			TreeSet<String> result = new TreeSet<>();
			if (values != null) result.addAll(values);
			return Collections.unmodifiableNavigableSet(result);
		}
	}

	public OwnershipLedger {
		ModpackId.requireValid(modpackId);
		TreeMap<String, Entry> sorted = new TreeMap<>();
		if (entries != null) sorted.putAll(entries);
		for (var entry : sorted.entrySet()) {
			if (!entry.getKey().equals(entry.getValue().logicalPath())) throw new IllegalArgumentException("Ledger entry key does not match logical path");
		}
		entries = Collections.unmodifiableNavigableMap(sorted);
		String expected = digest(modpackId, entries);
		if (digest == null || !digest.equals(expected)) throw new IllegalArgumentException("Ledger digest does not match content");
	}

	public OwnershipLedger(String modpackId, Map<String, Entry> entries) {
		this(modpackId, toNavigableMap(entries), digest(modpackId, entries));
	}

	public Jsons.OwnershipLedgerFields toFields() {
		Jsons.OwnershipLedgerFields fields = new Jsons.OwnershipLedgerFields();
		fields.modpackId = modpackId;
		fields.entries = new ArrayList<>();
		for (Entry entry : entries.values()) {
			Jsons.OwnershipLedgerFields.EntryFields serialized = new Jsons.OwnershipLedgerFields.EntryFields();
			serialized.logicalPath = entry.logicalPath();
			serialized.historicalHashes = entry.historicalHashes().stream()
					.map(value -> new Jsons.OwnershipLedgerFields.ContentFields(value.sha1(), value.size())).toList();
			serialized.historicalGroupIds = new TreeSet<>(entry.historicalGroupIds());
			serialized.firstPublishedGenerationId = entry.firstPublishedGenerationId();
			serialized.lastPublishedGenerationId = entry.lastPublishedGenerationId();
			serialized.currentStatus = entry.currentStatus().name();
			fields.entries.add(serialized);
		}
		fields.digest = digest;
		return fields;
	}

	public static OwnershipLedger fromFields(Jsons.OwnershipLedgerFields fields) {
		if (fields == null || fields.entries == null) throw new IllegalArgumentException("Ownership ledger is missing");
		Map<String, Entry> entries = new TreeMap<>();
		for (Jsons.OwnershipLedgerFields.EntryFields serialized : fields.entries) {
			if (serialized == null || serialized.historicalHashes == null) throw new IllegalArgumentException("Ownership ledger entry is incomplete");
			Set<Content> hashes = new TreeSet<>(CONTENT_ORDER);
			for (Jsons.OwnershipLedgerFields.ContentFields content : serialized.historicalHashes) {
				if (content == null) throw new IllegalArgumentException("Ownership ledger content is incomplete");
				hashes.add(new Content(content.sha1, content.size));
			}
			Entry entry = new Entry(serialized.logicalPath, hashes, serialized.historicalGroupIds, serialized.firstPublishedGenerationId,
					serialized.lastPublishedGenerationId, parseStatus(serialized.currentStatus));
			if (entries.put(entry.logicalPath(), entry) != null) throw new IllegalArgumentException("Duplicate ledger path: " + entry.logicalPath());
		}
		return new OwnershipLedger(fields.modpackId, new TreeMap<>(entries), fields.digest);
	}

	public static OwnershipLedger empty(String modpackId) {
		return new OwnershipLedger(modpackId, Map.of());
	}

	public static OwnershipLedger materialize(OwnershipLedger parent, GroupManifest manifest, String generationId) {
		Objects.requireNonNull(parent, "parent");
		Objects.requireNonNull(manifest, "manifest");
		String currentGeneration = requireGenerationReference(generationId, "generation ID");
		if (!parent.modpackId().equals(manifest.modpackId())) throw new IllegalArgumentException("Ledger and catalogue modpack IDs disagree");
		Map<String, CurrentPath> current = currentPaths(manifest);
		OwnershipDelta delta = OwnershipDelta.between(parent, manifest);
		Map<String, Entry> result = new TreeMap<>();
		Set<String> paths = new TreeSet<>(parent.entries().keySet());
		paths.addAll(current.keySet());
		for (String path : paths) {
			Entry old = parent.entries().get(path);
			CurrentPath now = current.get(path);
			if (now == null) {
				if (old == null) continue;
				Status status = old.currentStatus() == Status.PRESENT ? Status.TOMBSTONE : old.currentStatus();
				String last = old.currentStatus() == Status.PRESENT ? currentGeneration : old.lastPublishedGenerationId();
				result.put(path, new Entry(path, old.historicalHashes(), old.historicalGroupIds(), old.firstPublishedGenerationId(), last, status));
				continue;
			}
			Set<Content> hashes = new TreeSet<>(CONTENT_ORDER);
			if (old != null) hashes.addAll(old.historicalHashes());
			hashes.add(now.content());
			Set<String> groups = new TreeSet<>();
			if (old != null) groups.addAll(old.historicalGroupIds());
			groups.addAll(now.groupIds());
			boolean changed = delta.changes().containsKey(path);
			String first = old == null ? currentGeneration : old.firstPublishedGenerationId();
			String last = changed ? currentGeneration : old.lastPublishedGenerationId();
			result.put(path, new Entry(path, hashes, groups, first, last, Status.PRESENT));
		}
		return new OwnershipLedger(parent.modpackId(), result);
	}

	public static OwnershipLedger materializeWithoutGeneration(OwnershipLedger parent, GroupManifest manifest) {
		return materialize(parent, manifest, PROVISIONAL_GENERATION);
	}

	public static OwnershipLedger rebuild(List<GenerationRecord> orderedChain) {
		if (orderedChain == null || orderedChain.isEmpty()) throw new IllegalArgumentException("Generation chain is empty");
		GenerationRecord first = Objects.requireNonNull(orderedChain.get(0), "Generation chain contains null record");
		OwnershipLedger ledger = empty(first.manifest().modpackId());
		String previousGenerationId = GenerationMetadata.ROOT_PARENT;
		for (GenerationRecord record : orderedChain) {
			Objects.requireNonNull(record, "Generation chain contains null record");
			if (!ledger.modpackId().equals(record.manifest().modpackId())) throw new IllegalArgumentException("Generation chain changes modpack ID");
			if (!record.metadata().parentGenerationId().equals(previousGenerationId))
				throw new IllegalArgumentException("Generation chain is not ordered by parent links");
			OwnershipLedger expected = materialize(ledger, record.manifest(), record.metadata().generationId());
			if (!expected.equals(record.ownershipLedger())) throw new IllegalArgumentException("Generation ledger does not match ordered parent state");
			ledger = expected;
			previousGenerationId = record.metadata().generationId();
		}
		return ledger;
	}

	public static String digest(String modpackId, Map<String, Entry> entries) {
		CanonicalEncoder encoder = new CanonicalEncoder().string("automodpack-ownership-ledger-v1").string(modpackId);
		TreeMap<String, Entry> sorted = new TreeMap<>();
		if (entries != null) sorted.putAll(entries);
		encoder.integer(sorted.size());
		for (Entry entry : sorted.values()) {
			encoder.string(entry.logicalPath()).integer(entry.historicalHashes().size());
			for (Content content : entry.historicalHashes()) encoder.string(content.sha1()).longValue(content.size());
			encoder.integer(entry.historicalGroupIds().size());
			for (String group : entry.historicalGroupIds()) encoder.string(group);
			/* Publication IDs are provenance metadata. Excluding them avoids a digest/identity cycle. */
			encoder.string(entry.currentStatus().name());
		}
		return GenerationIdentity.sha1Bytes(encoder.bytes());
	}

	private static Map<String, CurrentPath> currentPaths(GroupManifest manifest) {
		Map<String, CurrentPath> result = new TreeMap<>();
		for (var groupEntry : manifest.groups().entrySet()) for (var fileEntry : groupEntry.getValue().files().entrySet()) {
			String path = LogicalPath.normalize(fileEntry.getKey());
			GroupManifest.GroupFile file = fileEntry.getValue();
			CurrentPath next = new CurrentPath(new Content(file.sha1().toLowerCase(Locale.ROOT), file.size()), Set.of(groupEntry.getKey()));
			CurrentPath previous = result.putIfAbsent(path, next);
			if (previous != null && !previous.content().equals(next.content())) throw new IllegalArgumentException("Conflicting current ownership for path: " + path);
			if (previous != null) result.put(path, new CurrentPath(previous.content(), union(previous.groupIds(), next.groupIds())));
		}
		return result;
	}

	private static Set<String> union(Set<String> first, Set<String> second) {
		TreeSet<String> result = new TreeSet<>(first);
		result.addAll(second);
		return result;
	}

	private static NavigableMap<String, Entry> toNavigableMap(Map<String, Entry> entries) {
		return entries == null ? new TreeMap<>() : new TreeMap<>(entries);
	}

	private static Status parseStatus(String value) {
		try {
			return Status.valueOf(value);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid ownership ledger status", e);
		}
	}

	private static String requireGenerationReference(String value, String name) {
		if (value == null || !value.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid " + name);
		return value;
	}

	private record CurrentPath(Content content, Set<String> groupIds) {}
}
